package eu.kanade.tachiyomi.data.download

import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class DownloadQueueMigration(
    private val mangaHandler: MangaDatabaseHandler? = null,
    private val animeHandler: AnimeDatabaseHandler? = null,
) {
    suspend fun manga(parentId: Long, itemId: Long): DownloadQueueIds? {
        return (mangaHandler ?: Injekt.get<MangaDatabaseHandler>()).awaitOneOrNull {
            download_queue_aliasQueries.getByLegacyIds(parentId, itemId, ::DownloadQueueIds)
        }
    }

    suspend fun anime(parentId: Long, itemId: Long): DownloadQueueIds? {
        return (animeHandler ?: Injekt.get<AnimeDatabaseHandler>()).awaitOneOrNull {
            download_queue_aliasQueries.getByLegacyIds(parentId, itemId, ::DownloadQueueIds)
        }
    }
}

data class DownloadQueueIds(val parentId: Long?, val itemId: Long?)
