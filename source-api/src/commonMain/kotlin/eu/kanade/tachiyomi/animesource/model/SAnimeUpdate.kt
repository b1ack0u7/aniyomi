package eu.kanade.tachiyomi.animesource.model

/**
 * Holder for the updated information a source returns from
 * [eu.kanade.tachiyomi.animesource.AnimeSource.getAnimeUpdate].
 *
 * @since extensions-lib 18
 */
class SAnimeUpdate(val anime: SAnime, val episodes: List<SEpisode>)
