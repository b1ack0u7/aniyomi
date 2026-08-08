package eu.kanade.tachiyomi.animesource

import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SAnimeUpdate
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.util.awaitSingle
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import rx.Observable

/**
 * A basic interface for creating a source. It could be an online source, a local source, etc.
 */
interface AnimeSource {

    /**
     * ID for the source. Must be unique.
     */
    val id: Long

    /**
     * Name of the source.
     */
    val name: String

    val lang: String
        get() = ""

    /**
     * Fetches updated information for an anime.
     *
     * Depending on the provided flags or source availability, this may include updated anime
     * metadata, available episodes, or both. If a value is not requested, the existing provided
     * value can be returned as-is.
     *
     * Sources targeting older versions of the library are served by the default implementation,
     * which delegates to [getAnimeDetails] and [getEpisodeList].
     *
     * @since extensions-lib 18
     * @param anime the anime to fetch updates for.
     * @param episodes existing episodes of the anime. May be empty when [fetchEpisodes] is true,
     * since the caller then has no reason to load them.
     * @param fetchDetails whether to include updated anime details.
     * @param fetchEpisodes whether to include available episodes.
     */
    suspend fun getAnimeUpdate(
        anime: SAnime,
        episodes: List<SEpisode>,
        fetchDetails: Boolean,
        fetchEpisodes: Boolean,
    ): SAnimeUpdate = defaultAnimeUpdate(anime, episodes, fetchDetails, fetchEpisodes)

    /**
     * Get the updated details for a anime.
     *
     * @since extensions-lib 1.5
     * @param anime the anime to update.
     * @return the updated anime.
     */
    @Suppress("DEPRECATION")
    suspend fun getAnimeDetails(anime: SAnime): SAnime {
        return fetchAnimeDetails(anime).awaitSingle()
    }

    /**
     * Get all the available episodes for a anime.
     *
     * @since extensions-lib 1.5
     * @param anime the anime to update.
     * @return the episodes for the anime.
     */
    @Suppress("DEPRECATION")
    suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        return fetchEpisodeList(anime).awaitSingle()
    }

    /**
     * Get all the available seasons for an anime
     *
     * @since extensions-lib 16
     * @param anime the anime to fetch seasons for.
     * @return the anime list for the anime.
     */
    suspend fun getSeasonList(anime: SAnime): List<SAnime>

    /**
     * Get the list of hoster for an episode. The first hoster in the list should
     * be the preferred hoster.
     *
     * @since extensions-lib 16
     * @param episode the episode.
     * @return the hosters for the episode.
     */
    suspend fun getHosterList(episode: SEpisode): List<Hoster> = throw IllegalStateException("Not used")

    /**
     * Get the list of videos for a hoster.
     *
     * @since extensions-lib 16
     * @param hoster the hoster.
     * @return the videos for the hoster.
     */
    suspend fun getVideoList(hoster: Hoster): List<Video> = throw IllegalStateException("Not used")

    /**
     * Get the list of videos a episode has. Pages should be returned
     * in the expected order; the index is ignored.
     *
     * @since extensions-lib 1.5
     * @param episode the episode.
     * @return the videos for the episode.
     */
    @Suppress("DEPRECATION")
    suspend fun getVideoList(episode: SEpisode): List<Video> {
        return fetchVideoList(episode).awaitSingle()
    }

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getAnimeDetails"),
    )
    fun fetchAnimeDetails(anime: SAnime): Observable<SAnime> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getEpisodeList"),
    )
    fun fetchEpisodeList(anime: SAnime): Observable<List<SEpisode>> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getVideoList"),
    )
    fun fetchVideoList(episode: SEpisode): Observable<List<Video>> =
        throw IllegalStateException("Not used")
}

/**
 * Backing implementation of [AnimeSource.getAnimeUpdate], shared by the interfaces exposing it so
 * that sources built against any version of the library resolve an implementation.
 */
internal suspend fun AnimeSource.defaultAnimeUpdate(
    anime: SAnime,
    episodes: List<SEpisode>,
    fetchDetails: Boolean,
    fetchEpisodes: Boolean,
): SAnimeUpdate = supervisorScope {
    val deferredAnime = if (fetchDetails) async { getAnimeDetails(anime) } else null
    val deferredEpisodes = if (fetchEpisodes) async { getEpisodeList(anime) } else null
    SAnimeUpdate(
        anime = deferredAnime?.await() ?: anime,
        episodes = deferredEpisodes?.await() ?: episodes,
    )
}
