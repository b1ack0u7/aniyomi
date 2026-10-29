package eu.kanade.tachiyomi.ui.main

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import mihon.core.database.DatabaseInitializer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ExternalPlayerResultViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        stores.forEach { it.clear() }
        Dispatchers.resetMain()
    }

    @Test
    fun `failed migration suspends results while main can retry and save the result afterward`() = runTest(dispatcher) {
        var fail = true
        val database = DatabaseInitializer({}, { if (fail) error("migration failed") }, dispatcher)
        database.open()
        val model = model()
        val data = mockk<Intent>()
        val handled = mutableListOf<Intent>()
        val initialized = mutableListOf<Pair<Long, Long>>()
        model.start(database::awaitReady, { anime, episode, _ -> initialized.add(anime to episode) }, handled::add)
        model.enqueue(1, 2, data)
        runCurrent()
        handled shouldBe emptyList()
        initialized shouldBe emptyList()

        val retry = launch {
            fail = false
            database.open()
        }
        runCurrent()
        retry.isCompleted shouldBe true
        initialized shouldBe listOf(1L to 2L)
        handled shouldBe listOf(data)
    }

    @Test
    fun `reconnecting activity does not create a second result consumer`() = runTest(dispatcher) {
        var fail = true
        val database = DatabaseInitializer({}, { if (fail) error("migration failed") }, dispatcher)
        database.open()
        val model = model()
        val handled = mutableListOf<Intent>()
        model.start(database::awaitReady, { _, _, _ -> true }, handled::add)
        model.enqueue(1, 2, mockk())
        runCurrent()
        model.start(database::awaitReady, { _, _, _ -> true }) { error("duplicate collector") }
        fail = false
        database.open()
        runCurrent()
        handled.size shouldBe 1
    }

    @Test
    fun `pending result and target survive view model recreation`() = runTest(dispatcher) {
        val saved = SavedStateHandle()
        val first = model(saved)
        val data = mockk<Intent>()
        first.enqueue(3, 4, data, legacyIds = true)
        val snapshot = saved.keys().associateWith { saved.get<Any?>(it) }
        stores.last().clear()
        val restored = model(SavedStateHandle(snapshot))
        val handled = mutableListOf<Intent>()
        val targets = mutableListOf<Pair<Long, Long>>()
        restored.start({}, { anime, episode, legacyIds ->
            legacyIds shouldBe true
            targets.add(anime to episode)
        }, handled::add)
        runCurrent()
        targets shouldBe listOf(3L to 4L)
        handled shouldBe listOf(data)
    }

    @Test
    fun `queued results retain their own targets and are consumed once in order`() = runTest(dispatcher) {
        val model = model()
        val first = mockk<Intent>()
        val second = mockk<Intent>()
        val targets = mutableListOf<Pair<Long, Long>>()
        val handled = mutableListOf<Intent>()
        model.enqueue(1, 2, first)
        model.enqueue(3, 4, second)
        model.start({}, { anime, episode, _ -> targets.add(anime to episode) }, handled::add)
        runCurrent()
        targets shouldBe listOf(1L to 2L, 3L to 4L)
        handled shouldBe listOf(first, second)
        runCurrent()
        handled.size shouldBe 2
    }

    @Test
    fun `missing target never applies progress to a previously initialized episode`() = runTest(dispatcher) {
        val model = model()
        val first = mockk<Intent>()
        val second = mockk<Intent>()
        val handled = mutableListOf<Intent>()
        model.enqueue(1, 2, first)
        model.enqueue(3, 4, second)
        model.start({}, { anime, _, _ -> anime == 3L }, handled::add)
        runCurrent()
        handled shouldBe listOf(second)
    }

    @Test
    fun `null result and invalid ids do not enqueue work`() = runTest(dispatcher) {
        val model = model()
        model.start({}, { _, _, _ -> error("no initialization expected") }) { error("no result expected") }
        model.enqueue(1, 2, null)
        model.enqueue(0, 2, mockk())
        model.enqueue(1, -1, mockk())
        runCurrent()
    }

    private fun model(saved: SavedStateHandle = SavedStateHandle()) = ExternalPlayerResultViewModel(saved).also {
        stores.add(ViewModelStore().apply { put("external", it) })
    }
}
