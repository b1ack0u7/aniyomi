package eu.kanade.tachiyomi.ui.library

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.anime.model.AnimeLibrarySort
import tachiyomi.domain.library.manga.model.MangaLibrarySort
import tachiyomi.domain.library.anime.model.sort as animeSort
import tachiyomi.domain.library.manga.model.sort as mangaSort

class LibraryTracksFlowTest {

    @ParameterizedTest
    @EnumSource(LibraryType::class)
    fun `ordinary library never subscribes to tracks`(type: LibraryType) = runTest {
        val categories = MutableStateFlow(listOf(category()))
        val filters = MutableStateFlow(mapOf(1L to TriState.DISABLED))
        val observed = mutableListOf<Map<Long, List<Int>>>()
        var subscriptions = 0
        val collector = launch {
            libraryTracksFlow(categories, filters, type::usesTrackerSort) {
                subscriptions++
                flow { emit(mapOf(1L to listOf(10))) }
            }.collect { observed += it }
        }
        runCurrent()

        subscriptions shouldBe 0
        observed shouldBe listOf(emptyMap())
        categories.value = listOf(category(id = 2))
        filters.value = emptyMap()
        runCurrent()
        subscriptions shouldBe 0
        observed shouldBe listOf(emptyMap())
        collector.cancelAndJoin()
    }

    @ParameterizedTest
    @EnumSource(LibraryType::class)
    fun `tracker filters subscribe once then cancel and clear when disabled`(type: LibraryType) = runTest {
        val categories = MutableStateFlow(listOf(category()))
        val filters = MutableStateFlow(emptyMap<Long, TriState>())
        val tracks = MutableStateFlow(mapOf(1L to listOf(8)))
        var subscriptions = 0
        var cancellations = 0
        val observed = mutableListOf<Map<Long, List<Int>>>()
        val collector = launch {
            libraryTracksFlow(categories, filters, type::usesTrackerSort) {
                flow {
                    subscriptions++
                    try {
                        emitAll(tracks)
                    } finally {
                        cancellations++
                    }
                }
            }.collect { observed += it }
        }
        runCurrent()
        filters.value = mapOf(1L to TriState.ENABLED_IS)
        runCurrent()
        subscriptions shouldBe 1
        observed.last() shouldBe tracks.value

        filters.value = mapOf(1L to TriState.ENABLED_NOT)
        categories.value = listOf(category(id = 2))
        tracks.value = mapOf(1L to listOf(9))
        runCurrent()
        subscriptions shouldBe 1
        cancellations shouldBe 0
        observed.last() shouldBe tracks.value

        filters.value = mapOf(1L to TriState.DISABLED)
        runCurrent()
        cancellations shouldBe 1
        observed.last() shouldBe emptyMap()
        tracks.value = mapOf(1L to listOf(10))
        runCurrent()
        observed.last() shouldBe emptyMap()

        filters.value = mapOf(1L to TriState.ENABLED_IS)
        runCurrent()
        subscriptions shouldBe 2
        observed.last() shouldBe tracks.value
        collector.cancelAndJoin()
        cancellations shouldBe 2
    }

    @ParameterizedTest
    @EnumSource(LibraryType::class)
    fun `tracker score sort keeps tracks until neither sort nor filter needs them`(type: LibraryType) = runTest {
        val categories = MutableStateFlow(listOf(category()))
        val filters = MutableStateFlow(emptyMap<Long, TriState>())
        val tracks = MutableStateFlow(mapOf(1L to listOf(8)))
        var activeSubscriptions = 0
        val collector = launch {
            libraryTracksFlow(categories, filters, type::usesTrackerSort) {
                flow {
                    activeSubscriptions++
                    try {
                        emitAll(tracks)
                    } finally {
                        activeSubscriptions--
                    }
                }
            }.collect {}
        }
        runCurrent()
        activeSubscriptions shouldBe 0
        categories.value = listOf(category(flags = type.trackerSortFlag))
        runCurrent()
        activeSubscriptions shouldBe 1

        filters.value = mapOf(1L to TriState.ENABLED_NOT)
        runCurrent()
        categories.value = listOf(category())
        runCurrent()
        activeSubscriptions shouldBe 1
        filters.value = emptyMap()
        runCurrent()
        activeSubscriptions shouldBe 0
        collector.cancelAndJoin()
    }

    enum class LibraryType {
        ANIME,
        MANGA,
        ;

        val trackerSortFlag: Long
            get() = when (this) {
                ANIME -> AnimeLibrarySort.Type.TrackerMean.flag
                MANGA -> MangaLibrarySort.Type.TrackerMean.flag
            }

        fun usesTrackerSort(category: Category): Boolean = when (this) {
            ANIME -> category.animeSort.type == AnimeLibrarySort.Type.TrackerMean
            MANGA -> category.mangaSort.type == MangaLibrarySort.Type.TrackerMean
        }
    }

    private fun category(id: Long = 1, flags: Long = 0) = Category(
        id = id,
        name = "Category",
        order = id,
        flags = flags,
        hidden = false,
    )
}
