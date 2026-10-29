package eu.kanade.tachiyomi.data.backup.restore.restorers

import android.content.Context
import android.util.Log
import eu.kanade.tachiyomi.data.backup.create.BackupCreateJob
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.library.anime.AnimeLibraryUpdateJob
import eu.kanade.tachiyomi.data.library.manga.MangaLibraryUpdateJob
import eu.kanade.tachiyomi.source.sourcePreferences
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.plusAssign
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class PreferenceRestorer(
    private val context: Context,
    private val getMangaCategories: GetMangaCategories = Injekt.get(),
    private val getAnimeCategories: GetAnimeCategories = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
) {
    suspend fun restoreApp(
        preferences: List<BackupPreference>,
        backupMangaCategories: List<BackupCategory>?,
        backupAnimeCategories: List<BackupCategory>?,
    ): List<String> {
        val errors = mutableListOf<String>()
        val mangaMapping = backupMangaCategories?.let {
            recover(errors, "App settings: Failed to map manga categories") {
                categoryMapping(it, getMangaCategories.await())
            }
        }
        val animeMapping = backupAnimeCategories?.let {
            recover(errors, "App settings: Failed to map anime categories") {
                categoryMapping(it, getAnimeCategories.await())
            }
        }
        val categoryMappings =
            (LibraryPreferences.mangaCategoryPreferenceKeys + DownloadPreferences.mangaCategoryPreferenceKeys)
                .associateWith { mangaMapping } +
                (LibraryPreferences.animeCategoryPreferenceKeys + DownloadPreferences.animeCategoryPreferenceKeys)
                    .associateWith { animeMapping }
        errors += restorePreferences(preferences, preferenceStore, "App settings", categoryMappings)

        recover(errors, "App settings: Failed to schedule anime library updates") {
            AnimeLibraryUpdateJob.setupTask(context)
        }
        recover(errors, "App settings: Failed to schedule manga library updates") {
            MangaLibraryUpdateJob.setupTask(context)
        }
        recover(errors, "App settings: Failed to schedule backups") {
            BackupCreateJob.setupTask(context)
        }
        return errors
    }

    suspend fun restoreSource(preferences: List<BackupSourcePreferences>): List<String> {
        val errors = mutableListOf<String>()
        preferences.forEachIndexed { index, source ->
            val scope = "Source settings #${index + 1}"
            recover(errors, "$scope: Failed to open preferences") {
                val sourcePrefs = AndroidPreferenceStore(context, sourcePreferences(source.sourceKey))
                errors += restorePreferences(source.prefs, sourcePrefs, scope)
            }
        }
        return errors
    }

    private suspend fun restorePreferences(
        toRestore: List<BackupPreference>,
        preferenceStore: PreferenceStore,
        scope: String,
        categoryMappings: Map<String, Map<String, String>?> = emptyMap(),
    ): List<String> {
        if (toRestore.isEmpty()) return emptyList()
        val errors = mutableListOf<String>()
        val prefs = recover(errors, "$scope: Failed to read existing preferences") {
            preferenceStore.getAll()
        } ?: return errors
        toRestore.forEachIndexed { index, (key, value) ->
            recover(errors, "$scope: Failed to restore preference #${index + 1}") {
                when (value) {
                    is IntPreferenceValue -> {
                        if (prefs[key] is Int?) {
                            val newValue = if (key in categoryMappings) {
                                categoryMappings[key]?.let { mapping ->
                                    if (value.value == -1) -1 else mapping[value.value.toString()]?.toIntOrNull()
                                }
                            } else {
                                value.value
                            }

                            newValue?.let { preferenceStore.getInt(key).set(it) }
                        }
                    }
                    is LongPreferenceValue -> {
                        if (prefs[key] is Long?) {
                            preferenceStore.getLong(key).set(value.value)
                        }
                    }
                    is FloatPreferenceValue -> {
                        if (prefs[key] is Float?) {
                            preferenceStore.getFloat(key).set(value.value)
                        }
                    }
                    is StringPreferenceValue -> {
                        if (prefs[key] is String?) {
                            preferenceStore.getString(key).set(value.value)
                        }
                    }
                    is BooleanPreferenceValue -> {
                        if (prefs[key] is Boolean?) {
                            preferenceStore.getBoolean(key).set(value.value)
                        }
                    }
                    is StringSetPreferenceValue -> {
                        if (prefs[key] is Set<*>?) {
                            if (key in categoryMappings) {
                                val ids = value.value.mapNotNull { categoryMappings[key]?.get(it) }
                                if (ids.isNotEmpty()) preferenceStore.getStringSet(key) += ids
                            } else {
                                preferenceStore.getStringSet(key).set(value.value)
                            }
                        }
                    }
                }
            }
        }
        return errors
    }

    private inline fun <T> recover(errors: MutableList<String>, message: String, block: () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        errors += message
        Log.e("PreferenceRestorer", message)
        null
    }

    private fun categoryMapping(
        backupCategories: List<BackupCategory>,
        categories: List<Category>,
    ): Map<String, String> {
        val categoriesByName = categories.associateBy { it.name }
        return backupCategories.mapNotNull { backup ->
            categoriesByName[backup.name]?.let { backup.id.toString() to it.id.toString() }
        }.toMap() + (Category.UNCATEGORIZED_ID.toString() to Category.UNCATEGORIZED_ID.toString())
    }
}
