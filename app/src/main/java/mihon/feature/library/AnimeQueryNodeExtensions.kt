package mihon.feature.library

import eu.kanade.tachiyomi.ui.library.anime.AnimeLibraryItem
import mihon.domain.library.model.search.AndNode
import mihon.domain.library.model.search.ComparisonField
import mihon.domain.library.model.search.ComparisonQueryNode
import mihon.domain.library.model.search.EmptyQueryNode
import mihon.domain.library.model.search.FieldQueryNode
import mihon.domain.library.model.search.GeneralQueryNode
import mihon.domain.library.model.search.LibraryField
import mihon.domain.library.model.search.NotNode
import mihon.domain.library.model.search.OrNode
import mihon.domain.library.model.search.QueryNode
import tachiyomi.source.local.entries.anime.LocalAnimeSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

fun QueryNode.matches(item: AnimeLibraryItem): Boolean {
    return when (this) {
        is AndNode -> children.all { it.matches(item) }
        is OrNode -> children.any { it.matches(item) }
        is NotNode -> !child.matches(item)
        is EmptyQueryNode -> true
        is GeneralQueryNode -> matches(item)
        is FieldQueryNode -> matches(item)
        is ComparisonQueryNode -> matches(item)
    }
}

private fun GeneralQueryNode.matches(item: AnimeLibraryItem): Boolean {
    val anime = item.libraryAnime.anime

    val match = LibraryField.entries.any { field ->
        if (field.fieldOnly) return@any false

        when (field) {
            LibraryField.TITLE -> anime.title.contains(value, ignoreCase = true)
            LibraryField.AUTHOR -> anime.author?.contains(value, ignoreCase = true) ?: false
            LibraryField.ARTIST -> anime.artist?.contains(value, ignoreCase = true) ?: false
            LibraryField.DESCRIPTION -> anime.description?.contains(value, ignoreCase = true) ?: false
            LibraryField.GENRE -> anime.genre?.any { it.contains(value, ignoreCase = true) } ?: false
            LibraryField.SOURCE -> {
                item.sourceName.contains(value, ignoreCase = true) ||
                    (value.equals("local", ignoreCase = true) && anime.source == LocalAnimeSource.ID)
            }

            LibraryField.LANGUAGE, LibraryField.SOURCE_ID -> error("How did we get here?")
        }
    }
    return if (negated) !match else match
}

private fun FieldQueryNode.matches(item: AnimeLibraryItem): Boolean {
    val anime = item.libraryAnime.anime

    val match = when (field) {
        LibraryField.GENRE -> {
            if (value.isEmpty()) {
                anime.genre.isNullOrEmpty()
            } else {
                anime.genre?.any { it.contains(value, ignoreCase = true) } ?: false
            }
        }

        LibraryField.SOURCE -> {
            if (value.isEmpty()) {
                item.sourceName.isEmpty()
            } else {
                item.sourceName.contains(value, ignoreCase = true) ||
                    (value.equals("local", ignoreCase = true) && anime.source == LocalAnimeSource.ID)
            }
        }

        LibraryField.SOURCE_ID -> {
            value.toLongOrNull()?.let { it == anime.source } ?: false
        }

        else -> {
            val text = when (field) {
                LibraryField.TITLE -> anime.title
                LibraryField.AUTHOR -> anime.author
                LibraryField.ARTIST -> anime.artist
                LibraryField.DESCRIPTION -> anime.description
                LibraryField.LANGUAGE -> item.sourceLang

                LibraryField.GENRE, LibraryField.SOURCE, LibraryField.SOURCE_ID -> error("How did we get here?")
            }

            if (value.isEmpty()) {
                text.isNullOrEmpty()
            } else {
                text?.contains(value, ignoreCase = true) ?: false
            }
        }
    }

    return if (negated) !match else match
}

private fun ComparisonQueryNode.matches(item: AnimeLibraryItem): Boolean {
    val anime = item.libraryAnime.anime

    fun compareDates(date: Instant, value: String): Boolean? {
        val inputDate = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
        val animeDate = date.atZone(ZoneId.systemDefault()).toLocalDate()
        return queryComparator.apply(animeDate, inputDate)
    }

    val match = when (field) {
        ComparisonField.ID -> value.toLongOrNull()?.let { queryComparator.apply(anime.id, it) }

        ComparisonField.DATE_ADDED -> anime.dateAdded.takeIf {
            it > 0
        }?.let { compareDates(Instant.ofEpochMilli(it), value) }

        ComparisonField.FETCH_INTERVAL -> value.toIntOrNull()
            ?.let { queryComparator.apply(abs(anime.fetchInterval), it) }

        ComparisonField.NEXT_UPDATE -> anime.expectedNextUpdate?.takeIf {
            it.toEpochMilli() > 0
        }?.let { compareDates(it, value) }

        ComparisonField.UNREAD -> {
            value.toLongOrNull()?.let {
                queryComparator.apply(item.libraryAnime.unseenCount, it)
            }
        }

        ComparisonField.READ -> {
            value.toLongOrNull()?.let {
                queryComparator.apply(item.libraryAnime.seenCount, it)
            }
        }

        ComparisonField.TOTAL -> {
            value.toLongOrNull()?.let {
                queryComparator.apply(item.libraryAnime.totalCount, it)
            }
        }
    } ?: false

    return if (negated) !match else match
}
