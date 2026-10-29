package eu.kanade.tachiyomi.data.backup.restore

import android.content.ContentResolver
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LegacyBackup
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.library.manga.MangaLibraryUpdateJob
import eu.kanade.tachiyomi.source.sourcePreferences
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import java.io.ByteArrayInputStream
import java.io.File

class BackupPreferenceErrorReportTest {

    @TempDir
    lateinit var directory: File

    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val notifier = mockk<BackupNotifier>(relaxed = true)
    private val uri = mockk<Uri>()
    private val mangaCategories = mockk<GetMangaCategories>()
    private val animeCategories = mockk<GetAnimeCategories>()
    private val appValues = mutableMapOf<String, Any>("changed_type" to "local")
    private val sourceValues = mutableMapOf<String, Any>("changed_type" to "local")
    private lateinit var preferences: PreferenceRestorer

    @BeforeEach
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        every { context.stringResource(any()) } returns "Settings"
        mockkStatic(::sourcePreferences)
        mockkObject(AnimeLibraryUpdateJob.Companion, MangaLibraryUpdateJob.Companion, BackupCreateJob.Companion)
        every { AnimeLibraryUpdateJob.setupTask(any(), any()) } just Runs
        every { MangaLibraryUpdateJob.setupTask(any(), any()) } just Runs
        every { BackupCreateJob.setupTask(any(), any()) } just Runs
        every { context.cacheDir } returns directory
        every { context.externalCacheDir } returns directory
        every { context.contentResolver } returns resolver
        every { sourcePreferences("secret-source") } returns sharedPreferences(sourceValues)
        coEvery { mangaCategories.await() } returns listOf(Category(101, "Manga", 0, 0, false))
        coEvery { animeCategories.await() } returns listOf(Category(102, "Anime", 0, 0, false))
        preferences = PreferenceRestorer(
            context,
            mangaCategories,
            animeCategories,
            AndroidPreferenceStore(context, sharedPreferences(appValues)),
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
        unmockkStatic(::sourcePreferences)
        unmockkObject(AnimeLibraryUpdateJob.Companion, MangaLibraryUpdateJob.Companion, BackupCreateJob.Companion)
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `actual restore reports partial app and source failures without secrets in current and legacy files`(
        legacy: Boolean,
    ) = runBlocking<Unit> {
        val backup = backup(includeFailures = true)
        restore(backup, legacy)

        appValues["later"] shouldBe 42
        sourceValues["later"] shouldBe 43
        appValues["changed_type"] shouldBe "local"
        sourceValues["changed_type"] shouldBe "local"
        assertCategoryMappings()
        sourceValues["animelib_update_categories"] shouldBe setOf("7")
        val log = File(directory, "aniyomi_restore_error.txt")
        val lines = log.readLines().map { it.substringAfter("] ") }.toSet()
        lines shouldBe setOf(
            "App settings: Failed to restore preference #1",
            "Source settings #1: Failed to restore preference #1",
        )
        verify(exactly = 1) {
            notifier.showRestoreComplete(any(), 2, directory.path, log.name, false)
        }
        directory.listFiles()!!.none { it.name.startsWith("restore-") } shouldBe true
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `intentional incompatible types and unmapped category IDs do not produce an error report`(
        legacy: Boolean,
    ) = runBlocking<Unit> {
        restore(backup(includeFailures = false), legacy)

        assertCategoryMappings()
        appValues["changed_type"] shouldBe "local"
        sourceValues["changed_type"] shouldBe "local"
        File(directory, "aniyomi_restore_error.txt").exists() shouldBe false
        verify(exactly = 1) { notifier.showRestoreComplete(any(), 0, any(), any(), false) }
    }

    @Test
    fun `deselected app and source settings never write preferences or report their failures`() = runBlocking<Unit> {
        restore(backup(includeFailures = true), legacy = false, appSettings = false, sourceSettings = false)
        appValues shouldBe mapOf("changed_type" to "local")
        sourceValues shouldBe mapOf("changed_type" to "local")
        verify(exactly = 1) { notifier.showRestoreComplete(any(), 0, any(), any(), false) }
        verify(exactly = 0) { Log.e(any(), any()) }
    }

    private suspend fun restore(
        backup: Backup,
        legacy: Boolean,
        appSettings: Boolean = true,
        sourceSettings: Boolean = true,
    ) {
        val bytes = if (legacy) {
            ProtoBuf.encodeToByteArray(
                LegacyBackup.serializer(),
                LegacyBackup(
                    backupPreferences = backup.backupPreferences,
                    backupSourcePreferences = backup.backupSourcePreferences,
                    backupCategories = backup.backupCategories,
                    backupAnimeCategories = backup.backupAnimeCategories,
                ),
            )
        } else {
            ProtoBuf.encodeToByteArray(Backup.serializer(), backup)
        }
        every { resolver.openInputStream(uri) } answers { ByteArrayInputStream(bytes) }
        BackupRestorer(
            context = context,
            notifier = notifier,
            isSync = false,
            animeCategoriesRestorer = mockk(relaxed = true),
            mangaCategoriesRestorer = mockk(relaxed = true),
            preferenceRestorer = preferences,
            animeExtensionRepoRestorer = mockk(),
            mangaExtensionRepoRestorer = mockk(),
            customButtonRestorer = mockk(),
            animeRestorer = mockk(),
            mangaRestorer = mockk(),
            extensionsRestorer = mockk(),
            backupDecoder = BackupDecoder(context, ProtoBuf),
            parser = ProtoBuf,
        ).restore(
            uri,
            RestoreOptions(
                libraryEntries = false,
                categories = true,
                appSettings = appSettings,
                extensionRepoSettings = false,
                customButtons = false,
                sourceSettings = sourceSettings,
                extensions = false,
            ),
        )
    }

    private fun backup(includeFailures: Boolean): Backup {
        val failures = if (includeFailures) {
            listOf(BackupPreference("secret-key", StringPreferenceValue("secret-value")))
        } else {
            emptyList()
        }
        return Backup(
            isLegacy = false,
            backupCategories = listOf(BackupCategory("Manga", id = 7)),
            backupAnimeCategories = listOf(BackupCategory("Anime", id = 7)),
            backupPreferences = failures + categoryPreferences(true) + categoryPreferences(false) + listOf(
                BackupPreference("later", IntPreferenceValue(42)),
                BackupPreference("changed_type", IntPreferenceValue(99)),
            ),
            backupSourcePreferences = listOf(
                BackupSourcePreferences(
                    "secret-source",
                    failures + listOf(
                        BackupPreference("later", IntPreferenceValue(43)),
                        BackupPreference("changed_type", IntPreferenceValue(99)),
                        BackupPreference("animelib_update_categories", StringSetPreferenceValue(setOf("7"))),
                    ),
                ),
            ),
        )
    }

    private fun categoryPreferences(manga: Boolean): List<BackupPreference> {
        val keys = if (manga) {
            LibraryPreferences.mangaCategoryPreferenceKeys + DownloadPreferences.mangaCategoryPreferenceKeys
        } else {
            LibraryPreferences.animeCategoryPreferenceKeys + DownloadPreferences.animeCategoryPreferenceKeys
        }
        return keys.map { key ->
            BackupPreference(
                key,
                if (key == "default_category" || key == "default_anime_category") {
                    IntPreferenceValue(7)
                } else {
                    StringSetPreferenceValue(setOf("7", "999"))
                },
            )
        }
    }

    private fun assertCategoryMappings() {
        listOf(true to 101, false to 102).forEach { (manga, id) ->
            categoryPreferences(manga).forEach { (key, value) ->
                appValues[key] shouldBe if (value is IntPreferenceValue) id else setOf(id.toString())
            }
        }
    }

    private fun sharedPreferences(values: MutableMap<String, Any>): SharedPreferences {
        val shared = mockk<SharedPreferences>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { shared.all } answers { values.toMap() }
        every { shared.getStringSet(any(), any()) } answers {
            (values[firstArg()] as? Set<*>)?.filterIsInstance<String>()?.toSet() ?: secondArg()
        }
        every { shared.edit() } returns editor
        every { editor.putString("secret-key", any()) } throws IllegalArgumentException("secret-value")
        every { editor.putInt(any(), any()) } answers {
            values[firstArg()] = secondArg<Int>()
            editor
        }
        every { editor.putStringSet(any(), any()) } answers {
            values[firstArg()] = secondArg<Set<String>>()
            editor
        }
        return shared
    }
}
