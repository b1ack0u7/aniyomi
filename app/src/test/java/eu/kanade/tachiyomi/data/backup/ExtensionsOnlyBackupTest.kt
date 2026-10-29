package eu.kanade.tachiyomi.data.backup

import android.content.ContentResolver
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.backup.create.BackupCreator
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.AnimeBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.AnimeCategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.AnimeSourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.ExtensionsBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaCategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaSourcesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.LegacyBackup
import eu.kanade.tachiyomi.data.backup.restore.BackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import eu.kanade.tachiyomi.data.backup.restore.restorers.AnimeRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionsRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.extension.anime.AnimeExtensionManager
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar
import java.io.File
import java.util.zip.GZIPOutputStream

class ExtensionsOnlyBackupTest {

    @TempDir
    lateinit var directory: File

    private val originalInjekt = Injekt

    @BeforeEach
    fun setUp() {
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton<ProtoBuf>(ProtoBuf)
        mockkStatic(UniFile::class)
        mockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
    }

    @AfterEach
    fun tearDown() {
        Injekt = originalInjekt
        unmockkStatic(UniFile::class)
        unmockkStatic("tachiyomi.core.common.i18n.LocalizeKt")
    }

    @Test
    fun `extensions alone enable creation and options retain their independent selections`() {
        val empty = emptyOptions()
        empty.canCreate() shouldBe false
        empty.copy(chapters = true, history = true, tracking = true, readEntries = true).canCreate() shouldBe false
        empty.copy(privateSettings = true).canCreate() shouldBe false
        val extensions = empty.copy(extensions = true)
        extensions.canCreate() shouldBe true
        BackupOptions.fromBooleanArray(extensions.asBooleanArray()) shouldBe extensions
        listOf(
            extensions.copy(libraryEntries = true),
            extensions.copy(categories = true),
            extensions.copy(appSettings = true),
            extensions.copy(sourceSettings = true),
            extensions.copy(extensionRepoSettings = true),
            extensions.copy(customButton = true),
        ).forEach { it.canCreate() shouldBe true }
        RestoreOptions.fromBooleanArray(extensionsRestoreOptions().asBooleanArray()) shouldBe extensionsRestoreOptions()
        extensionsRestoreOptions().canRestore() shouldBe true
    }

    @ParameterizedTest
    @EnumSource(value = Stack::class, names = ["MANGA", "ANIME", "BOTH"])
    fun `extension APKs without titles pass real creation validation decoding and restore dispatch`(stack: Stack) =
        runBlocking<Unit> {
            val fixture = Fixture(stack)
            fixture.creator.backup(fixture.uri, emptyOptions().copy(extensions = true)) shouldBe "content://backup/test"
            fixture.file.readBytes().take(2) shouldBe listOf(0x1f.toByte(), 0x8b.toByte())
            val decoder = BackupDecoder(fixture.context, ProtoBuf)
            val summary = decoder.decodeMetadata(fixture.uri)
            summary.mangaCount shouldBe 0
            summary.animeCount shouldBe 0
            summary.metadata.backupExtensions shouldBe fixture.extensions
            summary.metadata.backupManga shouldBe emptyList()
            summary.metadata.backupAnime shouldBe emptyList()
            fixture.validator.validate(fixture.uri) shouldBe BackupFileValidator.Results(emptyList(), emptyList())
            verify(exactly = 0) { fixture.uniFile.delete() }

            fixture.restore(libraryEntries = false)
            fixture.restore(libraryEntries = true)
            verify(exactly = 2) { fixture.extensionsRestorer.restoreExtensions(fixture.extensions) }
            coVerify(exactly = 0) { fixture.mangaRestorer.restore(any(), any(), any()) }
            coVerify(exactly = 0) { fixture.animeRestorer.restore(any(), any(), any(), any()) }

            GZIPOutputStream(fixture.file.outputStream()).use {
                it.write(
                    ProtoBuf.encodeToByteArray(
                        LegacyBackup.serializer(),
                        LegacyBackup(
                            backupExtensions = fixture.extensions,
                        ),
                    ),
                )
            }
            decoder.decodeMetadata(fixture.uri).isLegacy shouldBe true
            fixture.validator.validate(fixture.uri) shouldBe BackupFileValidator.Results(emptyList(), emptyList())
            fixture.restore(libraryEntries = true)
            verify(exactly = 3) { fixture.extensionsRestorer.restoreExtensions(fixture.extensions) }
            verify(exactly = 3) { fixture.notifier.showRestoreComplete(any(), 0, any(), any(), false) }
        }

    @Test
    fun `extensions and categories together retain both metadata selections without titles`() = runBlocking<Unit> {
        val fixture = Fixture(Stack.BOTH)
        fixture.creator.backup(fixture.uri, emptyOptions().copy(extensions = true, categories = true))
        val summary = BackupDecoder(fixture.context, ProtoBuf).decodeMetadata(fixture.uri)
        summary.mangaCount shouldBe 0
        summary.animeCount shouldBe 0
        summary.metadata.backupExtensions shouldBe fixture.extensions
        summary.metadata.backupCategories.single().hidden shouldBe true
        summary.metadata.backupAnimeCategories.single().hidden shouldBe false
        fixture.validator.validate(fixture.uri) shouldBe BackupFileValidator.Results(emptyList(), emptyList())
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `empty selection or selected extensions without installed APKs rejects and deletes empty backup`(
        extensions: Boolean,
    ) = runBlocking<Unit> {
        val fixture = Fixture(Stack.NONE)
        shouldThrow<IllegalStateException> {
            fixture.creator.backup(fixture.uri, emptyOptions().copy(extensions = extensions))
        }.message shouldBe "Empty backup"
        verify(exactly = 1) { fixture.uniFile.delete() }
    }

    @Test
    fun `format marker alone stays empty while category or extension metadata counts as content`() {
        val buffer = Buffer()
        val writer = BackupWriter(buffer, ProtoBuf)
        writer.writeMetadata(Backup(isLegacy = false))
        buffer.size shouldBe 3L
        writer.isEmpty shouldBe true
        writer.writeMetadata(Backup(backupExtensions = listOf(BackupExtension("extension", byteArrayOf(1)))))
        writer.isEmpty shouldBe false
        val categories = BackupWriter(Buffer(), ProtoBuf)
        categories.writeMetadata(Backup(backupCategories = listOf(BackupCategory("Category"))))
        categories.isEmpty shouldBe false
    }

    enum class Stack { MANGA, ANIME, BOTH, NONE }

    private inner class Fixture(stack: Stack) {
        val context = mockk<Context>()
        private val resolver = mockk<ContentResolver>()
        private val packageManager = mockk<PackageManager>()
        val uri = mockk<Uri>()
        val file = File(directory, "backup.tachibk")
        val uniFile = mockk<UniFile>(relaxed = true)
        private val animeManager = mockk<AnimeExtensionManager>()
        private val mangaManager = mockk<MangaExtensionManager>()
        val extensions = buildList {
            if (stack == Stack.ANIME || stack == Stack.BOTH) {
                add(BackupExtension("qa.anime.extension", byteArrayOf(1, 3, 5, 7)))
            }
            if (stack == Stack.MANGA || stack == Stack.BOTH) {
                add(BackupExtension("qa.manga.extension", byteArrayOf(2, 4, 6, 8)))
            }
        }
        private val animeSourceManager = mockk<AnimeSourceManager>()
        private val mangaSourceManager = mockk<MangaSourceManager>()
        val validator = BackupFileValidator(context, animeSourceManager, mangaSourceManager, mockk())
        private val mangaCategories = mockk<MangaCategoriesBackupCreator>()
        private val animeCategories = mockk<AnimeCategoriesBackupCreator>()
        val notifier = mockk<BackupNotifier>(relaxed = true)
        private val mangaCreator = mockk<MangaBackupCreator>()
        private val animeCreator = mockk<AnimeBackupCreator>()
        private val mangaSources = mockk<MangaSourcesBackupCreator>()
        private val animeSources = mockk<AnimeSourcesBackupCreator>()
        val extensionsRestorer = mockk<ExtensionsRestorer>(relaxed = true)
        val mangaRestorer = mockk<MangaRestorer>()
        val animeRestorer = mockk<AnimeRestorer>()
        val creator: BackupCreator

        init {
            every { uri.toString() } returns "content://backup/test"
            every { context.contentResolver } returns resolver
            every { context.packageManager } returns packageManager
            every { context.cacheDir } returns directory
            every { resolver.openInputStream(uri) } answers { file.inputStream() }
            every { UniFile.fromUri(context, uri) } returns uniFile
            every { uniFile.isFile } returns true
            every { uniFile.uri } returns uri
            every { uniFile.openOutputStream() } answers { file.outputStream() }
            every { context.stringResource(any()) } returns "Progress"
            every { context.stringResource(MR.strings.empty_backup_error) } returns "Empty backup"
            coEvery { mangaCategories() } returns listOf(BackupCategory("Manga category", hidden = true))
            coEvery { animeCategories() } returns listOf(BackupCategory("Anime category", hidden = false))
            coEvery { mangaCreator(any()) } returns emptyFlow()
            coEvery { animeCreator(any()) } returns emptyFlow()
            coEvery { mangaSources(any()) } returns emptyList()
            coEvery { animeSources(any()) } returns emptyList()
            coEvery { mangaRestorer.getExistingMatcher() } returns { _, _ -> false }
            coEvery { animeRestorer.getExistingMatcher() } returns { _, _ -> false }
            coEvery { animeManager.getInstalledExtensions() } returns extensions
                .filter { it.pkgName.contains("anime") }
                .map { extension -> mockk<AnimeExtension.Installed> { every { pkgName } returns extension.pkgName } }
            coEvery { mangaManager.getInstalledExtensions() } returns extensions
                .filter { it.pkgName.contains("manga") }
                .map { extension -> mockk<MangaExtension.Installed> { every { pkgName } returns extension.pkgName } }
            extensions.forEach { extension ->
                val apk = File(directory, "${extension.pkgName}.apk").apply { writeBytes(extension.apk) }
                val info = mockk<ApplicationInfo>().apply { publicSourceDir = apk.absolutePath }
                every {
                    packageManager.getApplicationInfo(extension.pkgName, PackageManager.GET_META_DATA)
                } returns info
            }
            creator = BackupCreator(
                context = context,
                isAutoBackup = false,
                parser = ProtoBuf,
                backupPreferences = mockk(),
                animeCategoriesBackupCreator = animeCategories,
                mangaCategoriesBackupCreator = mangaCategories,
                animeBackupCreator = animeCreator,
                mangaBackupCreator = mangaCreator,
                preferenceBackupCreator = mockk(),
                animeExtensionRepoBackupCreator = mockk(),
                mangaExtensionRepoBackupCreator = mockk(),
                customButtonBackupCreator = mockk(),
                animeSourcesBackupCreator = animeSources,
                mangaSourcesBackupCreator = mangaSources,
                extensionsBackupCreator = ExtensionsBackupCreator(context, animeManager, mangaManager),
                backupFileValidator = validator,
            )
        }

        suspend fun restore(libraryEntries: Boolean) {
            BackupRestorer(
                context = context,
                notifier = notifier,
                isSync = false,
                animeCategoriesRestorer = mockk(),
                mangaCategoriesRestorer = mockk(),
                preferenceRestorer = mockk(),
                animeExtensionRepoRestorer = mockk(),
                mangaExtensionRepoRestorer = mockk(),
                customButtonRestorer = mockk(),
                animeRestorer = animeRestorer,
                mangaRestorer = mangaRestorer,
                extensionsRestorer = extensionsRestorer,
                backupDecoder = BackupDecoder(context, ProtoBuf),
                parser = ProtoBuf,
            ).restore(uri, extensionsRestoreOptions().copy(libraryEntries = libraryEntries))
        }
    }

    companion object {
        private fun emptyOptions() = BackupOptions.fromBooleanArray(BooleanArray(12))

        private fun extensionsRestoreOptions() =
            RestoreOptions.fromBooleanArray(BooleanArray(7)).copy(extensions = true)
    }
}
