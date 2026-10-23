package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupDecoder
import eu.kanade.tachiyomi.data.backup.BackupEntryType
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupCustomButtons
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.restore.restorers.AnimeCategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.AnimeExtensionRepoRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.AnimeRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.CustomButtonRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.ExtensionsRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaCategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaExtensionRepoRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceRestorer
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.serialization.protobuf.ProtoBuf
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.i18n.aniyomi.AYMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class BackupRestorer(
    private val context: Context,
    private val notifier: BackupNotifier,
    private val isSync: Boolean,

    private val animeCategoriesRestorer: AnimeCategoriesRestorer = AnimeCategoriesRestorer(),
    private val mangaCategoriesRestorer: MangaCategoriesRestorer = MangaCategoriesRestorer(),
    private val preferenceRestorer: PreferenceRestorer = PreferenceRestorer(context),
    private val animeExtensionRepoRestorer: AnimeExtensionRepoRestorer = AnimeExtensionRepoRestorer(),
    private val mangaExtensionRepoRestorer: MangaExtensionRepoRestorer = MangaExtensionRepoRestorer(),
    private val customButtonRestorer: CustomButtonRestorer = CustomButtonRestorer(),
    private val animeRestorer: AnimeRestorer = AnimeRestorer(),
    private val mangaRestorer: MangaRestorer = MangaRestorer(),
    private val extensionsRestorer: ExtensionsRestorer = ExtensionsRestorer(context),
    private val backupDecoder: BackupDecoder = BackupDecoder(context),
    private val parser: ProtoBuf = Injekt.get(),
) {

    private var restoreAmount = 0
    private val restoreProgress = AtomicInteger()
    private val errors = CopyOnWriteArrayList<Pair<Date, String>>()

    /**
     * Mapping of source ID to source name from backup data
     */
    private var animeSourceMapping: Map<Long, String> = emptyMap()
    private var mangaSourceMapping: Map<Long, String> = emptyMap()

    suspend fun restore(uri: Uri, options: RestoreOptions) {
        val startTime = System.currentTimeMillis()

        restoreFromFile(uri, options)

        val time = System.currentTimeMillis() - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(
            time,
            errors.size,
            logFile.parent,
            logFile.name,
            isSync,
        )
    }

    private suspend fun restoreFromFile(uri: Uri, options: RestoreOptions) {
        val summary = backupDecoder.decodeMetadata(uri)
        val backup = summary.metadata

        // Store source mapping for error messages
        val backupAnimeMaps = backup.backupAnimeSources
        animeSourceMapping = backupAnimeMaps.associate { it.sourceId to it.name }
        val backupMangaMaps = backup.backupSources
        mangaSourceMapping = backupMangaMaps.associate { it.sourceId to it.name }

        if (options.libraryEntries) {
            restoreAmount += summary.mangaCount + summary.animeCount
        }
        if (options.categories) {
            restoreAmount += 2 // +2 for anime and manga categories
        }
        if (options.appSettings) {
            restoreAmount += 1
        }
        if (options.extensionRepoSettings) {
            restoreAmount += backup.backupAnimeExtensionRepo.size + backup.backupMangaExtensionRepo.size
        }
        if (options.customButtons) {
            restoreAmount += 1
        }
        if (options.sourceSettings) {
            restoreAmount += 1
        }
        if (options.extensions) {
            restoreAmount += 1
        }

        val cache = File.createTempFile("restore-", ".tmp", context.cacheDir)
        BackupRestoreStore(cache, parser).use { store ->
            if (options.libraryEntries) {
                val mangaExists = mangaRestorer.getExistingMatcher()
                val animeExists = animeRestorer.getExistingMatcher()
                backupDecoder.openReader(uri, summary.isLegacy, collectMetadata = false).use { reader ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        when (reader.nextEntry()) {
                            BackupEntryType.MANGA -> {
                                val entry = reader.readMangaEntry()
                                store.addManga(entry, mangaExists(entry.source, entry.url))
                            }
                            BackupEntryType.ANIME -> {
                                val entry = reader.readAnimeEntry()
                                store.addAnime(entry, animeExists(entry.source, entry.url))
                            }
                            null -> break
                        }
                    }
                }
            }

            coroutineScope {
                if (options.categories) {
                    restoreCategories(
                        backupAnimeCategories = backup.backupAnimeCategories,
                        backupMangaCategories = backup.backupCategories,
                    ).join()
                }
                if (options.appSettings) {
                    restoreAppPreferences(
                        backup.backupPreferences,
                        backup.backupCategories.takeIf {
                            options.categories
                        },
                    )
                }
                if (options.sourceSettings) {
                    restoreSourcePreferences(backup.backupSourcePreferences)
                }
                if (options.libraryEntries) {
                    restoreAnime(store, if (options.categories) backup.backupAnimeCategories else emptyList())
                    restoreManga(store, if (options.categories) backup.backupCategories else emptyList())
                }
                if (options.extensionRepoSettings) {
                    restoreExtensionRepos(backup.backupAnimeExtensionRepo, backup.backupMangaExtensionRepo)
                }
                if (options.customButtons) {
                    restoreCustomButtons(backup.backupCustomButton)
                }
                if (options.extensions) {
                    restoreExtensions(backup.backupExtensions)
                }

                // TODO: optionally trigger online library + tracker update
            }
        }
    }

    private fun CoroutineScope.restoreCategories(
        backupAnimeCategories: List<BackupCategory>,
        backupMangaCategories: List<BackupCategory>,
    ) = launch {
        ensureActive()
        animeCategoriesRestorer(backupAnimeCategories)
        mangaCategoriesRestorer(backupMangaCategories)

        restoreProgress.addAndGet(2)
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.categories),
            restoreProgress.get(),
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreAnime(
        store: BackupRestoreStore,
        backupAnimeCategories: List<BackupCategory>,
    ) = launch {
        val entries = store.animeEntries
        val byId = entries.filter { it.backupId != null }.associateBy { it.backupId }
        val restoredIds = mutableMapOf<Long, Long>()
        val plan = orderAnimeRestore(entries)
        for (index in plan.ordered) {
            ensureActive()
            try {
                val parent = index.parentId?.let(byId::get)
                val parentId = parent?.let { restoredIds[it.offset] }
                check(parent == null || parentId != null) { "Anime parent could not be restored" }
                val entry = store.readAnime(index)
                restoredIds[index.offset] = animeRestorer.restore(entry, backupAnimeCategories, parentId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val sourceName = animeSourceMapping[index.source] ?: index.source.toString()
                errors.add(Date() to "${index.title} [$sourceName]: ${e.message}")
            }
            restoreProgress.incrementAndGet()
            notifier.showRestoreProgress(index.title, restoreProgress.get(), restoreAmount, isSync)
        }
        entries.filter { it.offset in plan.invalid }.forEach { index ->
            val sourceName = animeSourceMapping[index.source] ?: index.source.toString()
            errors.add(Date() to "${index.title} [$sourceName]: Cyclic anime season relationship")
            restoreProgress.incrementAndGet()
            notifier.showRestoreProgress(index.title, restoreProgress.get(), restoreAmount, isSync)
        }
    }

    private fun CoroutineScope.restoreManga(
        store: BackupRestoreStore,
        backupMangaCategories: List<BackupCategory>,
    ) = launch {
        store.mangaEntries.forEach { index ->
            ensureActive()
            val entry = store.readManga(index)
            try {
                mangaRestorer.restore(entry, backupMangaCategories)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val sourceName = mangaSourceMapping[entry.source] ?: entry.source.toString()
                errors.add(Date() to "${entry.title} [$sourceName]: ${e.message}")
            }

            restoreProgress.incrementAndGet()
            notifier.showRestoreProgress(entry.title, restoreProgress.get(), restoreAmount, isSync)
        }
    }

    private fun CoroutineScope.restoreAppPreferences(
        preferences: List<BackupPreference>,
        categories: List<BackupCategory>?,
    ) = launch {
        ensureActive()
        preferenceRestorer.restoreApp(
            preferences,
            categories,
        )

        restoreProgress.incrementAndGet()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.app_settings),
            restoreProgress.get(),
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreSourcePreferences(preferences: List<BackupSourcePreferences>) = launch {
        ensureActive()
        preferenceRestorer.restoreSource(preferences)

        restoreProgress.incrementAndGet()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.source_settings),
            restoreProgress.get(),
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreExtensionRepos(
        backupAnimeExtensionRepo: List<BackupExtensionRepos>,
        backupMangaExtensionRepo: List<BackupExtensionRepos>,
    ) = launch {
        backupAnimeExtensionRepo
            .forEach {
                ensureActive()

                try {
                    animeExtensionRepoRestorer(it)
                } catch (e: Exception) {
                    errors.add(Date() to "Error Adding Anime Repo: ${it.name} : ${e.message}")
                }

                restoreProgress.incrementAndGet()
                notifier.showRestoreProgress(
                    context.stringResource(MR.strings.extensionRepo_settings),
                    restoreProgress.get(),
                    restoreAmount,
                    isSync,
                )
            }

        backupMangaExtensionRepo
            .forEach {
                ensureActive()

                try {
                    mangaExtensionRepoRestorer(it)
                } catch (e: Exception) {
                    errors.add(Date() to "Error Adding Manga Repo: ${it.name} : ${e.message}")
                }

                restoreProgress.incrementAndGet()
                notifier.showRestoreProgress(
                    context.stringResource(MR.strings.extensionRepo_settings),
                    restoreProgress.get(),
                    restoreAmount,
                    isSync,
                )
            }
    }

    private fun CoroutineScope.restoreCustomButtons(customButtons: List<BackupCustomButtons>) = launch {
        ensureActive()
        customButtonRestorer(customButtons)

        restoreProgress.incrementAndGet()
        notifier.showRestoreProgress(
            context.stringResource(AYMR.strings.custom_button_settings),
            restoreProgress.get(),
            restoreAmount,
            isSync,
        )
    }

    private fun CoroutineScope.restoreExtensions(extensions: List<BackupExtension>) = launch {
        ensureActive()
        extensionsRestorer.restoreExtensions(extensions)

        restoreProgress.incrementAndGet()
        notifier.showRestoreProgress(
            context.stringResource(MR.strings.source_settings),
            restoreProgress.get(),
            restoreAmount,
            isSync,
        )
    }

    private fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("aniyomi_restore_error.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (e: Exception) {
            // Empty
        }
        return File("")
    }
}
