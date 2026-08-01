package eu.kanade.tachiyomi.ui.entries.suggestions

import eu.kanade.domain.entries.anime.model.toDomainAnime
import eu.kanade.domain.entries.manga.model.toDomainManga
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.data.suggestions.SuggestionItem
import eu.kanade.tachiyomi.data.suggestions.SuggestionMediaType
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.browse.anime.source.globalsearch.GlobalAnimeSearchScreen
import eu.kanade.tachiyomi.ui.browse.manga.source.globalsearch.GlobalMangaSearchScreen
import eu.kanade.tachiyomi.ui.entries.anime.AnimeScreen
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import tachiyomi.domain.entries.anime.interactor.NetworkToLocalAnime
import tachiyomi.domain.entries.manga.interactor.NetworkToLocalManga
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

// A suggestion from an installed source carries enough to materialize a local entry; external
// providers have no source to open, hence the null.
suspend fun SuggestionItem.toDirectEntryScreenOrNull(): Screen? {
    val target = nativeSourceTarget ?: return null
    return when (mediaType) {
        SuggestionMediaType.ANIME -> {
            val anime = SAnime.create().apply {
                url = target.url
                title = this@toDirectEntryScreenOrNull.title
                thumbnail_url = this@toDirectEntryScreenOrNull.thumbnailUrl
            }
            val localAnime = Injekt.get<NetworkToLocalAnime>().await(anime.toDomainAnime(target.sourceId))
            AnimeScreen(localAnime.id, fromSource = true)
        }
        SuggestionMediaType.MANGA -> {
            val manga = SManga.create().apply {
                url = target.url
                title = this@toDirectEntryScreenOrNull.title
                thumbnail_url = this@toDirectEntryScreenOrNull.thumbnailUrl
            }
            val localManga = Injekt.get<NetworkToLocalManga>().await(manga.toDomainManga(target.sourceId))
            MangaScreen(localManga.id, fromSource = true)
        }
    }
}

fun SuggestionItem.toGlobalSearchScreen(): Screen = when (mediaType) {
    SuggestionMediaType.ANIME -> GlobalAnimeSearchScreen(searchQuery)
    SuggestionMediaType.MANGA -> GlobalMangaSearchScreen(searchQuery)
}
