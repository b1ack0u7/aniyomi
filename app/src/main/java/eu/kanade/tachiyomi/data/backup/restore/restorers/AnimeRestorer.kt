package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeHistory
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeTracking
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import tachiyomi.data.AnimeUpdateStrategyColumnAdapter
import tachiyomi.data.FetchTypeColumnAdapter
import tachiyomi.data.handlers.anime.AnimeDatabaseHandler
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.entries.anime.interactor.AnimeFetchInterval
import tachiyomi.domain.entries.anime.interactor.GetAnimeByUrlAndSourceId
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.anime.interactor.InsertAnimeTrack
import tachiyomi.domain.track.anime.model.AnimeTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.ZonedDateTime
import java.util.Date
import kotlin.math.max

class AnimeRestorer(
    private val handler: AnimeDatabaseHandler = Injekt.get(),
    private val getCategories: GetAnimeCategories = Injekt.get(),
    private val getAnimeByUrlAndSourceId: GetAnimeByUrlAndSourceId = Injekt.get(),
    private val getEpisodesByAnimeId: GetEpisodesByAnimeId = Injekt.get(),
    private val updateAnime: UpdateAnime = Injekt.get(),
    private val getTracks: GetAnimeTracks = Injekt.get(),
    private val insertTrack: InsertAnimeTrack = Injekt.get(),
    fetchInterval: AnimeFetchInterval = Injekt.get(),
) {

    private var now = ZonedDateTime.now()
    private var currentFetchWindow = fetchInterval.getWindow(now)

    init {
        now = ZonedDateTime.now()
        currentFetchWindow = fetchInterval.getWindow(now)
    }

    suspend fun getExistingMatcher(): (Long, String) -> Boolean {
        val urlsBySource = handler.awaitList { animeQueries.getAllAnimeSourceAndUrl() }
            .groupBy({ it.source }, { it.url })
            .mapValues { (_, urls) -> urls.toHashSet() }
        return { source, url -> urlsBySource[source]?.contains(url) == true }
    }

    suspend fun restore(
        backupAnime: BackupAnime,
        backupCategories: List<BackupCategory>,
        parentId: Long?,
        onWarning: (String) -> Unit = {},
    ): Long {
        val (id, skippedHistory) = handler.await(inTransaction = true) {
            val dbAnime = findExistingAnime(backupAnime)
            val anime = backupAnime.getAnimeImpl().copy(parentId = parentId)
            val restoredAnime = if (dbAnime == null) {
                restoreNewAnime(anime)
            } else {
                restoreExistingAnime(anime, dbAnime)
            }

            val skippedHistory = restoreAnimeDetails(
                anime = restoredAnime,
                episodes = backupAnime.episodes,
                categories = backupAnime.categories,
                backupCategories = backupCategories,
                history = backupAnime.history,
                tracks = backupAnime.tracking,
            )
            restoredAnime.id to skippedHistory
        }
        if (skippedHistory > 0) {
            onWarning("$skippedHistory history entries could not be restored because their episode metadata is missing")
        }
        return id
    }

    private suspend fun findExistingAnime(backupAnime: BackupAnime): Anime? {
        return getAnimeByUrlAndSourceId.await(backupAnime.url, backupAnime.source)
    }

    private suspend fun restoreExistingAnime(anime: Anime, dbAnime: Anime): Anime {
        return if (anime.version > dbAnime.version) {
            updateAnime(dbAnime.copyFrom(anime).copy(id = dbAnime.id, parentId = anime.parentId))
        } else {
            updateAnime(anime.copyFrom(dbAnime).copy(id = dbAnime.id, parentId = anime.parentId))
        }
    }

    private fun Anime.copyFrom(newer: Anime): Anime {
        return this.copy(
            favorite = this.favorite || newer.favorite,
            author = newer.author,
            artist = newer.artist,
            description = newer.description,
            genre = newer.genre,
            thumbnailUrl = newer.thumbnailUrl,
            status = newer.status,
            initialized = this.initialized || newer.initialized,
            version = newer.version,
            fetchType = newer.fetchType,
            parentId = newer.parentId,
        )
    }

    private suspend fun updateAnime(anime: Anime): Anime {
        handler.await(true) {
            animeQueries.update(
                source = anime.source,
                url = anime.url,
                artist = anime.artist,
                author = anime.author,
                description = anime.description,
                genre = anime.genre?.joinToString(separator = ", "),
                title = anime.title,
                status = anime.status,
                thumbnailUrl = anime.thumbnailUrl,
                favorite = anime.favorite,
                lastUpdate = anime.lastUpdate,
                nextUpdate = null,
                calculateInterval = null,
                initialized = anime.initialized,
                viewer = anime.viewerFlags,
                episodeFlags = anime.episodeFlags,
                coverLastModified = anime.coverLastModified,
                dateAdded = anime.dateAdded,
                animeId = anime.id,
                updateStrategy = anime.updateStrategy.let(AnimeUpdateStrategyColumnAdapter::encode),
                version = anime.version,
                isSyncing = 1,
                fetchType = anime.fetchType.let(FetchTypeColumnAdapter::encode),
                parentId = anime.parentId,
                seasonFlags = anime.seasonFlags,
                seasonNumber = anime.seasonNumber,
                seasonSourceOrder = anime.seasonSourceOrder,
                backgroundUrl = anime.backgroundUrl,
                backgroundLastModified = anime.backgroundLastModified,
            )
        }
        return anime
    }

    private suspend fun restoreNewAnime(
        anime: Anime,
    ): Anime {
        return anime.copy(
            initialized = anime.description != null,
            id = insertAnime(anime),
            version = anime.version,
        )
    }

    private suspend fun restoreEpisodes(anime: Anime, backupEpisodes: List<BackupEpisode>) {
        val dbEpisodesByUrl = getEpisodesByAnimeId.await(anime.id)
            .associateBy { it.url }

        val (existingEpisodes, newEpisodes) = backupEpisodes
            .map { it.toEpisodeImpl() }
            .groupingBy { it.url }
            .reduce { _, kept, other ->
                kept.copy(
                    seen = kept.seen || other.seen,
                    bookmark = kept.bookmark || other.bookmark,
                    fillermark = kept.fillermark || other.fillermark,
                    lastSecondSeen = max(kept.lastSecondSeen, other.lastSecondSeen),
                    totalSeconds = max(kept.totalSeconds, other.totalSeconds),
                    scanlator = kept.scanlator?.takeUnless { it.isBlank() } ?: other.scanlator,
                    summary = kept.summary?.takeUnless { it.isBlank() } ?: other.summary,
                    previewUrl = kept.previewUrl?.takeUnless { it.isBlank() } ?: other.previewUrl,
                    dateFetch = listOf(kept.dateFetch, other.dateFetch).filter { it > 0 }.minOrNull() ?: 0,
                    dateUpload = max(kept.dateUpload, other.dateUpload),
                    lastModifiedAt = max(kept.lastModifiedAt, other.lastModifiedAt),
                )
            }
            .values
            .mapNotNull {
                val episode = it.copy(animeId = anime.id)

                val dbEpisode = dbEpisodesByUrl[episode.url]
                    ?: // New episode
                    return@mapNotNull episode

                if (episode.forComparison() == dbEpisode.forComparison()) {
                    // Same state; skip
                    return@mapNotNull null
                }

                // Update to an existing episode
                episode
                    .copyFrom(dbEpisode)
                    .copy(
                        id = dbEpisode.id,
                        scanlator = dbEpisode.scanlator?.takeUnless { it.isBlank() } ?: episode.scanlator,
                        summary = dbEpisode.summary?.takeUnless { it.isBlank() } ?: episode.summary,
                        previewUrl = dbEpisode.previewUrl?.takeUnless { it.isBlank() } ?: episode.previewUrl,
                        seen = episode.seen || dbEpisode.seen,
                        bookmark = episode.bookmark || dbEpisode.bookmark,
                        fillermark = episode.fillermark || dbEpisode.fillermark,
                        lastSecondSeen = max(episode.lastSecondSeen, dbEpisode.lastSecondSeen),
                        totalSeconds = max(episode.totalSeconds, dbEpisode.totalSeconds),
                    )
            }
            .partition { it.id > 0 }

        insertNewEpisodes(newEpisodes)
        updateExistingEpisodes(existingEpisodes)
    }

    private fun Episode.forComparison() =
        this.copy(id = 0L, animeId = 0L, dateFetch = 0L, dateUpload = 0L, lastModifiedAt = 0L, version = 0L)

    private suspend fun insertNewEpisodes(episodes: List<Episode>) {
        handler.await(true) {
            episodes.forEach { episode ->
                episodeQueries.insert(
                    episode.animeId,
                    episode.url,
                    episode.name,
                    episode.scanlator,
                    episode.seen,
                    episode.bookmark,
                    episode.lastSecondSeen,
                    episode.totalSeconds,
                    episode.episodeNumber,
                    episode.sourceOrder,
                    episode.dateFetch,
                    episode.dateUpload,
                    episode.version,
                    episode.summary,
                    episode.previewUrl,
                    episode.fillermark,
                )
            }
        }
    }

    private suspend fun updateExistingEpisodes(episodes: List<Episode>) {
        handler.await(true) {
            episodes.forEach { episode ->
                episodeQueries.update(
                    animeId = null,
                    url = null,
                    name = null,
                    scanlator = episode.scanlator,
                    summary = episode.summary,
                    previewUrl = episode.previewUrl,
                    seen = episode.seen,
                    bookmark = episode.bookmark,
                    fillermark = episode.fillermark,
                    lastSecondSeen = episode.lastSecondSeen,
                    totalSeconds = episode.totalSeconds,
                    episodeNumber = null,
                    sourceOrder = null,
                    dateFetch = null,
                    dateUpload = null,
                    episodeId = episode.id,
                    version = episode.version,
                    isSyncing = 0,
                )
            }
        }
    }

    /**
     * Inserts anime and returns id
     *
     * @return id of [Anime], null if not found
     */
    private suspend fun insertAnime(anime: Anime): Long {
        return handler.awaitOneExecutable(true) {
            animeQueries.insert(
                source = anime.source,
                url = anime.url,
                artist = anime.artist,
                author = anime.author,
                description = anime.description,
                genre = anime.genre,
                title = anime.title,
                status = anime.status,
                thumbnailUrl = anime.thumbnailUrl,
                favorite = anime.favorite,
                lastUpdate = anime.lastUpdate,
                nextUpdate = 0L,
                calculateInterval = 0L,
                initialized = anime.initialized,
                viewerFlags = anime.viewerFlags,
                episodeFlags = anime.episodeFlags,
                coverLastModified = anime.coverLastModified,
                dateAdded = anime.dateAdded,
                updateStrategy = anime.updateStrategy,
                version = anime.version,
                fetchType = anime.fetchType,
                parentId = anime.parentId,
                seasonFlags = anime.seasonFlags,
                seasonNumber = anime.seasonNumber,
                seasonSourceOrder = anime.seasonSourceOrder,
                backgroundUrl = anime.backgroundUrl,
                backgroundLastModified = anime.backgroundLastModified,
            )
            animeQueries.getExistingId(anime.source, anime.url)
        }
    }

    private suspend fun restoreAnimeDetails(
        anime: Anime,
        episodes: List<BackupEpisode>,
        categories: List<Long>,
        backupCategories: List<BackupCategory>,
        history: List<BackupAnimeHistory>,
        tracks: List<BackupAnimeTracking>,
    ): Int {
        restoreCategories(anime, categories, backupCategories)
        restoreEpisodes(anime, episodes)
        restoreTracking(anime, tracks)
        val skippedHistory = restoreHistory(anime.id, history)
        updateAnime.awaitUpdateFetchInterval(anime, now, currentFetchWindow)
        return skippedHistory
    }

    /**
     * Restores the categories a anime is in.
     *
     * @param anime the anime whose categories have to be restored.
     * @param categories the categories to restore.
     */
    private suspend fun restoreCategories(
        anime: Anime,
        categories: List<Long>,
        backupCategories: List<BackupCategory>,
    ) {
        val dbCategories = getCategories.await()
        val dbCategoriesByName = dbCategories.associateBy { it.name }

        val backupCategoriesByOrder = backupCategories.associateBy { it.order }

        val animeCategoriesToUpdate = categories.mapNotNull { backupCategoryOrder ->
            backupCategoriesByOrder[backupCategoryOrder]?.let { backupCategory ->
                dbCategoriesByName[backupCategory.name]?.let { dbCategory ->
                    Pair(anime.id, dbCategory.id)
                }
            }
        }

        if (animeCategoriesToUpdate.isNotEmpty()) {
            handler.await(true) {
                anime_categoryQueries.deleteAnimeCategoryByAnimeId(anime.id)
                animeCategoriesToUpdate.forEach { (animeId, categoryId) ->
                    anime_categoryQueries.insert(animeId, categoryId)
                }
            }
        }
    }

    private suspend fun restoreHistory(animeId: Long, backupHistory: List<BackupAnimeHistory>): Int {
        var skippedHistory = 0
        val mergedHistory = backupHistory.groupingBy { it.url }.reduce { _, kept, other ->
            kept.copy(
                lastRead = max(kept.lastRead, other.lastRead),
                episode = kept.episode?.takeIf { it.url == kept.url }
                    ?: other.episode?.takeIf { it.url == other.url },
            )
        }
        val toUpdate = mergedHistory.values.mapNotNull { history ->
            val dbHistory = handler.awaitOneOrNull { anime_historyQueries.getHistoryByEpisodeUrl(animeId, history.url) }
            val item = history.getHistoryImpl()

            if (dbHistory == null) {
                var episode = handler.awaitOneOrNull { episodeQueries.getEpisodeByUrlAndAnimeId(history.url, animeId) }
                if (episode == null) {
                    history.episode?.takeIf { it.url == history.url }?.let { metadata ->
                        insertNewEpisodes(
                            listOf(metadata.historyMetadata().toEpisodeImpl().copy(animeId = animeId)),
                        )
                        episode = handler.awaitOneOrNull {
                            episodeQueries.getEpisodeByUrlAndAnimeId(history.url, animeId)
                        }
                    }
                }
                return@mapNotNull if (episode == null) {
                    skippedHistory++
                    null
                } else {
                    // New history entry
                    item.copy(episodeId = episode!!.id)
                }
            }

            // Update history entry
            item.copy(
                id = dbHistory.id,
                episodeId = dbHistory.episode_id,
                seenAt = max(item.seenAt?.time ?: 0L, dbHistory.last_seen?.time ?: 0L)
                    .takeIf { it > 0L }
                    ?.let { Date(it) },
            )
        }

        if (toUpdate.isNotEmpty()) {
            handler.await(true) {
                toUpdate.forEach {
                    anime_historyQueries.upsert(
                        it.episodeId,
                        it.seenAt,
                    )
                }
            }
        }
        return skippedHistory
    }

    private suspend fun restoreTracking(anime: Anime, backupTracks: List<BackupAnimeTracking>) {
        val dbTrackByTrackerId = getTracks.await(anime.id).associateBy { it.trackerId }

        val (existingTracks, newTracks) = backupTracks
            .mapNotNull {
                val track = it.getTrackImpl()
                val dbTrack = dbTrackByTrackerId[track.trackerId]
                    ?: // New track
                    return@mapNotNull track.copy(
                        id = 0, // Let DB assign new ID
                        animeId = anime.id,
                    )

                if (track.forComparison() == dbTrack.forComparison()) {
                    // Same state; skip
                    return@mapNotNull null
                }

                // Update to an existing track
                dbTrack.copy(
                    remoteId = track.remoteId,
                    libraryId = track.libraryId,
                    lastEpisodeSeen = max(dbTrack.lastEpisodeSeen, track.lastEpisodeSeen),
                )
            }
            .partition { it.id > 0 }

        if (newTracks.isNotEmpty()) {
            insertTrack.awaitAll(newTracks)
        }
        if (existingTracks.isNotEmpty()) {
            handler.await(true) {
                existingTracks.forEach { track ->
                    anime_trackQueries.update(
                        track.animeId,
                        track.trackerId,
                        track.remoteId,
                        track.libraryId,
                        track.title,
                        track.lastEpisodeSeen,
                        track.totalEpisodes,
                        track.status,
                        track.score,
                        track.remoteUrl,
                        track.startDate,
                        track.finishDate,
                        track.private,
                        track.id,
                    )
                }
            }
        }
    }

    private fun AnimeTrack.forComparison() = this.copy(id = 0L, animeId = 0L)
}
