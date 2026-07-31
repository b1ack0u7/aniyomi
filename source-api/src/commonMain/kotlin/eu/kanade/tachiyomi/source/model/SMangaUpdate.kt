package eu.kanade.tachiyomi.source.model

/**
 * Holder for the updated information a source returns from
 * [eu.kanade.tachiyomi.source.MangaSource.getMangaUpdate].
 *
 * @since extensions-lib 1.6
 */
class SMangaUpdate(val manga: SManga, val chapters: List<SChapter>)
