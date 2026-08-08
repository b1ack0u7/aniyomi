package tachiyomi.domain.items.episode.interactor

import tachiyomi.domain.items.episode.model.Episode

class ShouldUpdateDbEpisode {

    fun await(dbEpisode: Episode, sourceEpisode: Episode): Boolean {
        return dbEpisode.scanlator != sourceEpisode.scanlator ||
            dbEpisode.name != sourceEpisode.name ||
            dbEpisode.episodeNumber != sourceEpisode.episodeNumber ||
            dbEpisode.sourceOrder != sourceEpisode.sourceOrder ||
            dbEpisode.summary != sourceEpisode.summary ||
            // Sources that don't expose a real upload date report the current time instead, which
            // differs on every fetch. Only a first-time date counts, otherwise the whole episode
            // list is rewritten on each library update.
            (dbEpisode.dateUpload == 0L && sourceEpisode.dateUpload != 0L) ||
            // These two are only ever filled in, never cleared, so a source that reports less than
            // the database already holds must not count as a change.
            (!dbEpisode.fillermark && sourceEpisode.fillermark) ||
            (dbEpisode.previewUrl.isNullOrBlank() && !sourceEpisode.previewUrl.isNullOrBlank())
    }
}
