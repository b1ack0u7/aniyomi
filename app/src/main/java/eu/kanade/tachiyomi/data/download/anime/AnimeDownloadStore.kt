package eu.kanade.tachiyomi.data.download.anime

import android.content.Context
import androidx.core.content.edit
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.download.DownloadQueueMigration
import eu.kanade.tachiyomi.data.download.anime.model.AnimeDownload
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * This class is used to persist active downloads across application restarts.
 */
class AnimeDownloadStore(
    context: Context,
    private val sourceManager: AnimeSourceManager = Injekt.get(),
    private val json: Json = Injekt.get(),
    private val getAnime: GetAnime = Injekt.get(),
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId = Injekt.get(),
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
    fun addAll(downloads: List<AnimeDownload>) {
        preferences.edit {
            downloads.forEach { download ->
                val key = getKey(download)
                val order = preferences.getString(key, null)?.let(::deserialize)?.order ?: counter++
                putString(key, serialize(download, order))
            }
        }
    }

    /**
     * Removes a download from the store.
     *
     * @param download the download to remove.
     */
    @Synchronized
    fun remove(download: AnimeDownload) {
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
    fun removeAll(downloads: List<AnimeDownload>) {
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
                if (key.startsWith("v1:anime:") || (value as? String)?.let(::deserialize) != null) remove(key)
            }
        }
    }

    /**
     * Returns the preference's key for the given download.
     *
     * @param download the download.
     */
    private fun getKey(download: AnimeDownload): String {
        return "v1:anime:${download.episode.id}"
    }

    /**
     * Returns the list of downloads to restore.
     */
    suspend fun restore(publishQueue: (List<AnimeDownload>) -> Unit = {}): List<AnimeDownload> {
        val snapshot = preferences.all.mapNotNull { (key, value) ->
            (value as? String)?.let { raw -> deserialize(raw)?.let { Triple(key, raw, it) } }
        }.sortedBy { it.third.order }
        val originalValues = snapshot.associate { it.first to it.second }
        val candidates = mutableListOf<Triple<String, String, Pair<AnimeDownloadObject, AnimeDownload>>>()
        val cachedParents = mutableMapOf<Long, Anime?>()
        val cachedItems = mutableMapOf<Long, Map<Long, Episode>>()
        for ((key, raw, obj) in snapshot) {
            val mapped = try {
                if (obj.identityVersion == 0) migration.anime(obj.animeId, obj.episodeId) else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                continue
            }
            val parentId = if (mapped == null) obj.animeId else mapped.parentId ?: continue
            val itemId = if (mapped == null) obj.episodeId else mapped.itemId ?: continue
            val entry = cachedParents.getOrPut(parentId) { getAnime.await(parentId) } ?: continue
            val source = sourceManager.get(entry.source) as? AnimeHttpSource ?: continue
            val items = cachedItems.getOrPut(parentId) {
                getEpisodesByAnimeId.await(parentId).associateBy(Episode::id)
            }
            val child = items[itemId] ?: continue
            candidates += Triple(
                key,
                raw,
                obj.copy(animeId = parentId, episodeId = itemId, identityVersion = 1) to
                    AnimeDownload(source, entry, child),
            )
        }
        return synchronized(this) {
            counter = maxOf(counter, (snapshot.maxOfOrNull { it.third.order } ?: -1) + 1)
            val current = preferences.all
            val valid = candidates.filter { (key, raw, _) -> current[key] == raw }
                .groupBy { it.third.first.episodeId }
            val downloads = mutableListOf<AnimeDownload>()
            preferences.edit(commit = true) {
                valid.forEach { (id, entries) ->
                    val canonicalKey = "v1:anime:$id"
                    val changed = current[canonicalKey] != originalValues[canonicalKey]
                    if (changed) {
                        // Honor a newer enqueue or removal instead of resurrecting the snapshot.
                        entries.forEach { (key, _, _) -> if (key != canonicalKey) remove(key) }
                        return@forEach
                    }
                    val (merged, download) = entries.first().third
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
    private fun serialize(download: AnimeDownload, order: Int): String {
        val obj = AnimeDownloadObject(download.anime.id, download.episode.id, order, identityVersion = 1)
        return json.encodeToString(obj)
    }

    /**
     * Restore a download from a string.
     *
     * @param string the download as string.
     */
    private fun deserialize(string: String): AnimeDownloadObject? {
        return try {
            json.decodeFromString<AnimeDownloadObject>(string)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Class used for download serialization
 *
 * @param animeId the id of the anime.
 * @param episodeId the id of the episode.
 * @param order the order of the download in the queue.
 */
@Serializable
private data class AnimeDownloadObject(
    val animeId: Long,
    val episodeId: Long,
    val order: Int,
    val identityVersion: Int = 0,
)
