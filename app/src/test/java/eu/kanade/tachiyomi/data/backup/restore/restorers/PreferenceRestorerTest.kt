package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.PreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.library.manga.MangaLibraryUpdateJob
import eu.kanade.tachiyomi.source.sourcePreferences
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

class PreferenceRestorerTest {

    private val values = mutableMapOf<String, Any>()
    private val store = mockk<PreferenceStore>()
    private val context = mockk<Context>()
    private val mangaCategories = mockk<GetMangaCategories>()
    private val animeCategories = mockk<GetAnimeCategories>()
    private val restorer = PreferenceRestorer(context, mangaCategories, animeCategories, store)

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        mockkStatic(::sourcePreferences)
        every { Log.e(any(), any()) } returns 0
        mockkObject(AnimeLibraryUpdateJob.Companion, MangaLibraryUpdateJob.Companion, BackupCreateJob.Companion)
        every { AnimeLibraryUpdateJob.setupTask(any(), any()) } just Runs
        every { MangaLibraryUpdateJob.setupTask(any(), any()) } just Runs
        every { BackupCreateJob.setupTask(any(), any()) } just Runs
        every { store.getAll() } answers { values.toMap() }
        every { store.getInt(any(), any()) } answers { preference(firstArg(), secondArg<Int>()) }
        every { store.getLong(any(), any()) } answers { preference(firstArg(), secondArg<Long>()) }
        every { store.getFloat(any(), any()) } answers { preference(firstArg(), secondArg<Float>()) }
        every { store.getString(any(), any()) } answers { preference(firstArg(), secondArg<String>()) }
        every { store.getBoolean(any(), any()) } answers { preference(firstArg(), secondArg<Boolean>()) }
        every { store.getStringSet(any(), any()) } answers { preference(firstArg(), secondArg<Set<String>>()) }
        coEvery { mangaCategories.await() } returns listOf(category(101, "Manga"), category(201, "Anime"))
        coEvery { animeCategories.await() } returns listOf(category(102, "Anime"), category(202, "Manga"))
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(AnimeLibraryUpdateJob.Companion, MangaLibraryUpdateJob.Companion, BackupCreateJob.Companion)
        unmockkStatic(::sourcePreferences)
        unmockkStatic(Log::class)
    }

    @Test
    fun `category key groups cover every current category preference without overlap`() {
        val library = LibraryPreferences(store)
        val downloads = DownloadPreferences(store)
        LibraryPreferences.mangaCategoryPreferenceKeys + DownloadPreferences.mangaCategoryPreferenceKeys shouldBe setOf(
            library.defaultMangaCategory().key(),
            library.mangaUpdateCategories().key(),
            library.mangaUpdateCategoriesExclude().key(),
            downloads.removeExcludeCategories().key(),
            downloads.downloadNewChapterCategories().key(),
            downloads.downloadNewChapterCategoriesExclude().key(),
        )
        LibraryPreferences.animeCategoryPreferenceKeys + DownloadPreferences.animeCategoryPreferenceKeys shouldBe setOf(
            library.defaultAnimeCategory().key(),
            library.animeUpdateCategories().key(),
            library.animeUpdateCategoriesExclude().key(),
            downloads.removeExcludeAnimeCategories().key(),
            downloads.downloadNewEpisodeCategories().key(),
            downloads.downloadNewEpisodeCategoriesExclude().key(),
        )
        LibraryPreferences.categoryPreferenceKeys + DownloadPreferences.categoryPreferenceKeys shouldBe
            Stack.entries.flatMap { it.setKeys + it.defaultKey }.toSet()
        (LibraryPreferences.mangaCategoryPreferenceKeys + DownloadPreferences.mangaCategoryPreferenceKeys)
            .intersect(LibraryPreferences.animeCategoryPreferenceKeys + DownloadPreferences.animeCategoryPreferenceKeys)
            .shouldBe(emptySet())
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `identical backup IDs with different names use only the destination stack`(stack: Stack) = runBlocking<Unit> {
        restorer.restoreApp(
            stack.preferences(7),
            listOf(BackupCategory("Manga", id = 7)),
            listOf(BackupCategory("Anime", id = 7)),
        )
        values[stack.defaultKey] shouldBe stack.localId
        stack.setKeys.forEach { values[it] shouldBe setOf(stack.localId.toString()) }
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `different backup IDs never map through the other stack`(stack: Stack) = runBlocking<Unit> {
        restorer.restoreApp(
            listOf(BackupPreference(stack.defaultKey, IntPreferenceValue(stack.backupId))) +
                stack.setKeys.map { BackupPreference(it, StringSetPreferenceValue(setOf("11", "22"))) },
            listOf(BackupCategory("Manga", id = 11)),
            listOf(BackupCategory("Anime", id = 22)),
        )
        values[stack.defaultKey] shouldBe stack.localId
        stack.setKeys.forEach { values[it] shouldBe setOf(stack.localId.toString()) }
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `category sets merge locally and repeated restore is idempotent`(stack: Stack) = runBlocking<Unit> {
        stack.setKeys.forEach { values[it] = setOf("999") }
        repeat(2) {
            restore(stack.preferences(7))
            stack.setKeys.forEach { values[it] shouldBe setOf("999", stack.localId.toString()) }
        }
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `empty category sets preserve local selections and stay empty on a fresh install`(stack: Stack) =
        runBlocking<Unit> {
            val preferences = stack.setKeys.map { BackupPreference(it, StringSetPreferenceValue(emptySet())) }
            restore(preferences)
            stack.setKeys.forEach { store.getStringSet(it).get() shouldBe emptySet() }
            stack.setKeys.forEach { values[it] = setOf("999") }
            restore(preferences)
            stack.setKeys.forEach { values[it] shouldBe setOf("999") }
        }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `missing backup IDs and missing destination names preserve local references`(stack: Stack) = runBlocking<Unit> {
        values[stack.defaultKey] = 999
        stack.setKeys.forEach { values[it] = setOf("999") }
        restorer.restoreApp(
            stack.preferences(7) + stack.setKeys.map {
                BackupPreference(it, StringSetPreferenceValue(setOf("7", "8")))
            },
            listOf(BackupCategory("Missing manga", id = 7)),
            listOf(BackupCategory("Missing anime", id = 7)),
        )
        values[stack.defaultKey] shouldBe 999
        stack.setKeys.forEach { values[it] shouldBe setOf("999") }
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `empty category metadata never imports raw IDs or uses the other stack map`(stack: Stack) = runBlocking<Unit> {
        restorer.restoreApp(
            stack.preferences(7),
            if (stack == Stack.MANGA) emptyList() else listOf(BackupCategory("Manga", id = 7)),
            if (stack == Stack.ANIME) emptyList() else listOf(BackupCategory("Anime", id = 7)),
        )
        values[stack.defaultKey] shouldBe null
        stack.setKeys.forEach { values[it] shouldBe null }
    }

    @Test
    fun `deselecting categories preserves both stacks and restores unrelated settings`() = runBlocking<Unit> {
        Stack.entries.forEach { stack ->
            values[stack.defaultKey] = 999
            stack.setKeys.forEach { values[it] = setOf("999") }
        }
        val before = values.toMap()
        restorer.restoreApp(
            Stack.entries.flatMap { it.preferences(7) } + BackupPreference("unrelated", IntPreferenceValue(42)),
            null,
            null,
        )
        values shouldBe before + ("unrelated" to 42)
        coVerify(exactly = 0) { mangaCategories.await() }
        coVerify(exactly = 0) { animeCategories.await() }
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `system category and prompt default survive empty category metadata`(stack: Stack) = runBlocking<Unit> {
        restorer.restoreApp(stack.preferences(0), emptyList(), emptyList())
        values[stack.defaultKey] shouldBe 0
        stack.setKeys.forEach { values[it] shouldBe setOf("0") }
        restorer.restoreApp(
            listOf(BackupPreference(stack.defaultKey, IntPreferenceValue(-1))),
            emptyList(),
            emptyList(),
        )
        values[stack.defaultKey] shouldBe -1
    }

    @Test
    fun `unrelated settings preserve all types and skip incompatible local types`() = runBlocking<Unit> {
        values["changed_type"] = "local"
        values["strings"] = setOf("local")
        restore(
            listOf(
                BackupPreference("integer", IntPreferenceValue(12)),
                BackupPreference("long", LongPreferenceValue(13L)),
                BackupPreference("float", FloatPreferenceValue(1.5f)),
                BackupPreference("string", StringPreferenceValue("value")),
                BackupPreference("boolean", BooleanPreferenceValue(true)),
                BackupPreference("strings", StringSetPreferenceValue(setOf("7", "22", "text"))),
                BackupPreference("changed_type", IntPreferenceValue(14)),
            ),
        )
        values shouldBe mapOf(
            "integer" to 12,
            "long" to 13L,
            "float" to 1.5f,
            "string" to "value",
            "boolean" to true,
            "strings" to setOf("7", "22", "text"),
            "changed_type" to "local",
        )
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `existing category preference types are not changed by restore`(stack: Stack) = runBlocking<Unit> {
        values[stack.defaultKey] = "wrong type"
        stack.setKeys.forEach { values[it] = 123 }
        val before = values.toMap()
        restore(stack.preferences(7))
        values shouldBe before
    }

    @Test
    fun `source keys matching application category keys are restored without category remapping`() = runBlocking<Unit> {
        val shared = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        val sourceValues = mutableMapOf<String, Any>()
        mockkStatic(::sourcePreferences)
        every { sourcePreferences("source") } returns shared
        every { shared.all } answers { sourceValues.toMap() }
        every { shared.edit() } returns editor
        every { editor.putInt(any(), any()) } answers {
            sourceValues[firstArg()] = secondArg<Int>()
            editor
        }
        every { editor.putStringSet(any(), any()) } answers {
            sourceValues[firstArg()] = secondArg<Set<String>>()
            editor
        }
        restorer.restoreSource(
            listOf(
                BackupSourcePreferences(
                    "source",
                    listOf(
                        BackupPreference("default_category", IntPreferenceValue(7)),
                        BackupPreference("animelib_update_categories", StringSetPreferenceValue(setOf("22"))),
                    ),
                ),
            ),
        )
        sourceValues shouldBe mapOf("default_category" to 7, "animelib_update_categories" to setOf("22"))
        values shouldBe emptyMap()
        coVerify(exactly = 0) { mangaCategories.await() }
        coVerify(exactly = 0) { animeCategories.await() }
    }

    @Test
    fun `protobuf category and preference contracts roundtrip with separate maps`() = runBlocking<Unit> {
        val backup = Backup(
            backupCategories = listOf(BackupCategory("Manga", id = 7)),
            backupAnimeCategories = listOf(BackupCategory("Anime", id = 7)),
            backupPreferences = Stack.entries.flatMap { it.preferences(7) },
        )
        val decoded = ProtoBuf.decodeFromByteArray(
            Backup.serializer(),
            ProtoBuf.encodeToByteArray(Backup.serializer(), backup),
        )
        restorer.restoreApp(decoded.backupPreferences, decoded.backupCategories, decoded.backupAnimeCategories)
        Stack.entries.forEach { stack ->
            values[stack.defaultKey] shouldBe stack.localId
            stack.setKeys.forEach { values[it] shouldBe setOf(stack.localId.toString()) }
        }
    }

    @ParameterizedTest
    @EnumSource(ValueType::class)
    fun `app write failures are sanitized and do not prevent later preferences`(type: ValueType) = runBlocking<Unit> {
        when (type) {
            ValueType.INT -> every { store.getInt("secret-key", any()) } returns failingPreference()
            ValueType.LONG -> every { store.getLong("secret-key", any()) } returns failingPreference()
            ValueType.FLOAT -> every { store.getFloat("secret-key", any()) } returns failingPreference()
            ValueType.STRING -> every { store.getString("secret-key", any()) } returns failingPreference()
            ValueType.BOOLEAN -> every { store.getBoolean("secret-key", any()) } returns failingPreference()
            ValueType.STRING_SET -> every {
                store.getStringSet("secret-key", any())
            } returns failingPreference()
        }
        val result = restorer.restoreApp(
            listOf(
                BackupPreference("secret-key", type.value),
                BackupPreference("later", IntPreferenceValue(42)),
            ),
            null,
            null,
        )
        result shouldBe listOf("App settings: Failed to restore preference #1")
        values shouldBe mapOf("later" to 42)
        verify(exactly = 1) { Log.e("PreferenceRestorer", result.single()) }
    }

    @ParameterizedTest
    @EnumSource(ValueType::class)
    fun `source write failures are sanitized and source restoration continues`(type: ValueType) = runBlocking<Unit> {
        val sourceValues = mutableMapOf<String, Any>()
        val editor = sourceStore("secret-source", sourceValues)
        when (type) {
            ValueType.INT -> every { editor.putInt("secret-key", any()) } throws IllegalStateException("secret-value")
            ValueType.LONG -> every { editor.putLong("secret-key", any()) } throws IllegalStateException("secret-value")
            ValueType.FLOAT -> every {
                editor.putFloat("secret-key", any())
            } throws IllegalStateException("secret-value")
            ValueType.STRING -> every {
                editor.putString("secret-key", any())
            } throws IllegalStateException("secret-value")
            ValueType.BOOLEAN -> every {
                editor.putBoolean("secret-key", any())
            } throws IllegalStateException("secret-value")
            ValueType.STRING_SET -> every {
                editor.putStringSet("secret-key", any())
            } throws IllegalStateException("secret-value")
        }
        val nextSourceValues = mutableMapOf<String, Any>()
        sourceStore("next", nextSourceValues)
        val result = restorer.restoreSource(
            listOf(
                BackupSourcePreferences(
                    "secret-source",
                    listOf(
                        BackupPreference("secret-key", type.value),
                        BackupPreference("later", IntPreferenceValue(42)),
                    ),
                ),
                BackupSourcePreferences("next", listOf(BackupPreference("next", IntPreferenceValue(43)))),
            ),
        )
        result shouldBe listOf("Source settings #1: Failed to restore preference #1")
        sourceValues shouldBe mapOf("later" to 42)
        nextSourceValues shouldBe mapOf("next" to 43)
        verify(exactly = 1) { Log.e("PreferenceRestorer", result.single()) }
    }

    @Test
    fun `incompatible types and intentional category omissions do not report errors`() = runBlocking<Unit> {
        values["integer"] = "local"
        restorer.restoreApp(
            Stack.entries.flatMap { it.preferences(7) } + BackupPreference("integer", IntPreferenceValue(42)),
            null,
            null,
        ) shouldBe emptyList()
        values shouldBe mapOf("integer" to "local")
        val sourceValues = mutableMapOf<String, Any>("integer" to "local")
        sourceStore("source", sourceValues)
        restorer.restoreSource(
            listOf(BackupSourcePreferences("source", listOf(BackupPreference("integer", IntPreferenceValue(42))))),
        ) shouldBe emptyList()
        sourceValues shouldBe mapOf("integer" to "local")
        verify(exactly = 0) { Log.e(any(), any()) }
    }

    @Test
    fun `app preference read failure is reported without unsafe writes`() = runBlocking<Unit> {
        every { store.getAll() } throws IllegalStateException("secret-value")
        restorer.restoreApp(listOf(BackupPreference("secret-key", IntPreferenceValue(42))), null, null) shouldBe
            listOf("App settings: Failed to read existing preferences")
        values shouldBe emptyMap()
    }

    @Test
    fun `source open and read failures are reported while later sources restore`() = runBlocking<Unit> {
        mockkStatic(::sourcePreferences)
        every { sourcePreferences("secret-source") } throws IllegalStateException("secret-value")
        val unreadable = mockk<SharedPreferences>()
        every { sourcePreferences("unreadable") } returns unreadable
        every { unreadable.all } throws IllegalStateException("secret-value")
        val sourceValues = mutableMapOf<String, Any>()
        sourceStore("next", sourceValues)
        val preferences = listOf(BackupPreference("restored", IntPreferenceValue(42)))
        restorer.restoreSource(
            listOf("secret-source", "unreadable", "next").map { BackupSourcePreferences(it, preferences) },
        ) shouldBe listOf(
            "Source settings #1: Failed to open preferences",
            "Source settings #2: Failed to read existing preferences",
        )
        sourceValues shouldBe mapOf("restored" to 42)
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `failed category lookup preserves references and the other stack restores`(stack: Stack) = runBlocking<Unit> {
        if (stack == Stack.MANGA) {
            coEvery { mangaCategories.await() } throws IllegalStateException("secret-value")
        } else {
            coEvery { animeCategories.await() } throws IllegalStateException("secret-value")
        }
        val other = Stack.entries.single { it != stack }
        values[stack.defaultKey] = 999
        restorer.restoreApp(
            Stack.entries.flatMap { it.preferences(7) } + BackupPreference("unrelated", IntPreferenceValue(42)),
            listOf(BackupCategory("Manga", id = 7)),
            listOf(BackupCategory("Anime", id = 7)),
        ) shouldBe listOf("App settings: Failed to map ${stack.name.lowercase()} categories")
        values[stack.defaultKey] shouldBe 999
        stack.setKeys.forEach { values[it] shouldBe null }
        values[other.defaultKey] shouldBe other.localId
        other.setKeys.forEach { values[it] shouldBe setOf(other.localId.toString()) }
        values["unrelated"] shouldBe 42
    }

    @Test
    fun `scheduling failure is reported and other schedules continue`() = runBlocking<Unit> {
        every { AnimeLibraryUpdateJob.setupTask(any(), any()) } throws IllegalStateException("secret-value")
        restorer.restoreApp(listOf(BackupPreference("restored", IntPreferenceValue(42))), null, null) shouldBe
            listOf("App settings: Failed to schedule anime library updates")
        values shouldBe mapOf("restored" to 42)
        verify(exactly = 1) { MangaLibraryUpdateJob.setupTask(any(), any()) }
        verify(exactly = 1) { BackupCreateJob.setupTask(any(), any()) }
    }

    @Test
    fun `cancellation during app restore is propagated without continuing writes`() = runBlocking<Unit> {
        every { store.getInt("cancel", any()) } throws CancellationException("cancelled")
        assertThrows<CancellationException> {
            restorer.restoreApp(
                listOf(
                    BackupPreference("cancel", IntPreferenceValue(1)),
                    BackupPreference("later", IntPreferenceValue(42)),
                ),
                null,
                null,
            )
        }
        values shouldBe emptyMap()
        verify(exactly = 0) { Log.e(any(), any()) }
    }

    @Test
    fun `cancellation during source restore is propagated without continuing sources`() = runBlocking<Unit> {
        mockkStatic(::sourcePreferences)
        every { sourcePreferences("cancel") } throws CancellationException("cancelled")
        assertThrows<CancellationException> {
            restorer.restoreSource(
                listOf("cancel", "later").map {
                    BackupSourcePreferences(it, listOf(BackupPreference("restored", IntPreferenceValue(42))))
                },
            )
        }
        verify(exactly = 0) { sourcePreferences("later") }
        verify(exactly = 0) { Log.e(any(), any()) }
    }

    private fun <T> failingPreference(): Preference<T> = mockk {
        every { set(any()) } throws IllegalArgumentException("secret-value", RuntimeException("nested-secret"))
    }

    private fun sourceStore(key: String, values: MutableMap<String, Any>): SharedPreferences.Editor {
        val shared = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { sourcePreferences(key) } returns shared
        every { shared.all } answers { values.toMap() }
        every { shared.edit() } returns editor
        every { editor.putInt(any(), any()) } answers {
            values[firstArg()] = secondArg<Int>()
            editor
        }
        return editor
    }

    enum class ValueType(val value: PreferenceValue) {
        INT(IntPreferenceValue(1)),
        LONG(LongPreferenceValue(1)),
        FLOAT(FloatPreferenceValue(1f)),
        STRING(StringPreferenceValue("secret-value")),
        BOOLEAN(BooleanPreferenceValue(true)),
        STRING_SET(StringSetPreferenceValue(setOf("secret-value"))),
    }

    private suspend fun restore(preferences: List<BackupPreference>) = restorer.restoreApp(
        preferences,
        listOf(BackupCategory("Manga", id = 7)),
        listOf(BackupCategory("Anime", id = 7)),
    )

    private inline fun <reified T : Any> preference(key: String, default: T): Preference<T> = mockk {
        every { key() } returns key
        every { get() } answers { values[key] as? T ?: default }
        every { set(any()) } answers { values[key] = firstArg<T>() }
    }

    enum class Stack(val defaultKey: String, val setKeys: Set<String>, val localId: Int, val backupId: Int) {
        MANGA(
            "default_category",
            setOf(
                "library_update_categories",
                "library_update_categories_exclude",
                "remove_exclude_categories",
                "download_new_categories",
                "download_new_categories_exclude",
            ),
            101,
            11,
        ),
        ANIME(
            "default_anime_category",
            setOf(
                "animelib_update_categories",
                "animelib_update_categories_exclude",
                "remove_exclude_anime_categories",
                "download_new_anime_categories",
                "download_new_anime_categories_exclude",
            ),
            102,
            22,
        ),
        ;

        fun preferences(id: Int) = listOf(BackupPreference(defaultKey, IntPreferenceValue(id))) +
            setKeys.map { BackupPreference(it, StringSetPreferenceValue(setOf(id.toString()))) }
    }

    private fun category(id: Long, name: String) = Category(id, name, 0, 0, false)
}
