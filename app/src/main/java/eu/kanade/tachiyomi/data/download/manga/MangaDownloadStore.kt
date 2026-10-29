package eu.kanade.tachiyomi.data.download.manga

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.data.download.DownloadQueueMigration
import eu.kanade.tachiyomi.data.download.manga.model.MangaDownload
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.source.manga.service.MangaSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * This class is used to persist active downloads across application restarts.
 */
class MangaDownloadStore(
    context: Context,
    private val sourceManager: MangaSourceManager = Injekt.get(),
    private val json: Json = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val migration: DownloadQueueMigration = DownloadQueueMigration(),
) {

    /**
     * Preference file where active downloads are stored.
     */
    private val preferences = context.getSharedPreferences("active_downloads", Context.MODE_PRIVATE)

    /**
     * Counter used to keep the queue order.
     */
    private var counter = 0

    /**
     * Adds a list of downloads to the store.
     *
     * @param downloads the list of downloads to add.
     */
    @Synchronized
    fun addAll(downloads: List<MangaDownload>) {
        preferences.edit {
            downloads.forEach { download ->
                val key = getKey(download)
                val order = preferences.getString(key, null)?.let(::deserialize)?.order ?: counter++
                putString(key, serialize(download, order))
            }
        }
    }

    @Synchronized
    fun update(download: MangaDownload) {
        val key = getKey(download)
        val order = preferences.getString(key, null)?.let(::deserialize)?.order ?: return
        preferences.edit {
            putString(key, serialize(download, order))
        }
    }

    /**
     * Removes a download from the store.
     *
     * @param download the download to remove.
     */
    @Synchronized
    fun remove(download: MangaDownload) {
        preferences.edit {
            remove(getKey(download))
        }
    }

    /**
     * Removes a list of downloads from the store.
     *
     * @param downloads the download to remove.
     */
    @Synchronized
    fun removeAll(downloads: List<MangaDownload>) {
        preferences.edit {
            downloads.forEach { remove(getKey(it)) }
        }
    }

    /**
     * Removes all the downloads from the store.
     */
    @Synchronized
    fun clear() {
        preferences.edit {
            preferences.all.forEach { (key, value) ->
                if (key.startsWith("v1:manga:") || (value as? String)?.let(::deserialize) != null) remove(key)
            }
        }
    }

    /**
     * Returns the preference's key for the given download.
     *
     * @param download the download.
     */
    private fun getKey(download: MangaDownload): String {
        return "v1:manga:${download.chapter.id}"
    }

    /**
     * Returns the list of downloads to restore.
     */
    suspend fun restore(publishQueue: (List<MangaDownload>) -> Unit = {}): List<MangaDownload> {
        val snapshot = preferences.all.mapNotNull { (key, value) ->
            (value as? String)?.let { raw -> deserialize(raw)?.let { Triple(key, raw, it) } }
        }.sortedBy { it.third.order }
        val originalValues = snapshot.associate { it.first to it.second }
        val candidates = mutableListOf<Triple<String, String, Pair<DownloadObject, MangaDownload>>>()
        val cachedParents = mutableMapOf<Long, Manga?>()
        val cachedItems = mutableMapOf<Long, Map<Long, Chapter>>()
        for ((key, raw, obj) in snapshot) {
            val mapped = try {
                if (obj.identityVersion == 0) migration.manga(obj.mangaId, obj.chapterId) else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                continue
            }
            val parentId = if (mapped == null) obj.mangaId else mapped.parentId ?: continue
            val itemId = if (mapped == null) obj.chapterId else mapped.itemId ?: continue
            val entry = cachedParents.getOrPut(parentId) { getManga.await(parentId) } ?: continue
            val source = sourceManager.get(entry.source) as? HttpSource ?: continue
            val items = cachedItems.getOrPut(parentId) {
                getChaptersByMangaId.await(parentId).associateBy(Chapter::id)
            }
            val child = items[itemId] ?: continue
            candidates += Triple(
                key,
                raw,
                obj.copy(mangaId = parentId, chapterId = itemId, identityVersion = 1) to
                    MangaDownload(source, entry, child).apply {
                        if (obj.failed) status = MangaDownload.State.ERROR
                    },
            )
        }
        return synchronized(this) {
            counter = maxOf(counter, (snapshot.maxOfOrNull { it.third.order } ?: -1) + 1)
            val current = preferences.all
            val valid = candidates.filter { (key, raw, _) -> current[key] == raw }
                .groupBy { it.third.first.chapterId }
            val downloads = mutableListOf<MangaDownload>()
            preferences.edit(commit = true) {
                valid.forEach { (id, entries) ->
                    val canonicalKey = "v1:manga:$id"
                    val changed = current[canonicalKey] != originalValues[canonicalKey]
                    if (changed) {
                        // Honor a newer enqueue or removal instead of resurrecting the snapshot.
                        entries.forEach { (key, _, _) -> if (key != canonicalKey) remove(key) }
                        return@forEach
                    }
                    val (first, download) = entries.first().third
                    val merged = first.copy(failed = entries.any { it.third.first.failed })
                    if (merged.failed) download.status = MangaDownload.State.ERROR
                    entries.forEach { (key, _, _) -> remove(key) }
                    putString(canonicalKey, json.encodeToString(merged))
                    downloads += download
                }
            }
            publishQueue(downloads)
            downloads
        }
    }

    /**
     * Converts a download to a string.
     *
     * @param download the download to serialize.
     */
    private fun serialize(download: MangaDownload, order: Int = counter++): String {
        val obj = DownloadObject(
            download.manga.id,
            download.chapter.id,
            order,
            failed = download.status == MangaDownload.State.ERROR,
            identityVersion = 1,
        )
        return json.encodeToString(obj)
    }

    /**
     * Restore a download from a string.
     *
     * @param string the download as string.
     */
    private fun deserialize(string: String): DownloadObject? {
        return try {
            json.decodeFromString<DownloadObject>(string)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Class used for download serialization
 *
 * @param mangaId the id of the manga.
 * @param chapterId the id of the chapter.
 * @param order the order of the download in the queue.
 */
@Serializable
private data class DownloadObject(
    val mangaId: Long,
    val chapterId: Long,
    val order: Int,
    val failed: Boolean = false,
    val identityVersion: Int = 0,
)
