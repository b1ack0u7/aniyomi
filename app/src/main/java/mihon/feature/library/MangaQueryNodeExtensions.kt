package mihon.feature.library

import eu.kanade.tachiyomi.ui.library.manga.MangaLibraryItem
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
import tachiyomi.source.local.entries.manga.LocalMangaSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

fun QueryNode.matches(item: MangaLibraryItem): Boolean {
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

private fun GeneralQueryNode.matches(item: MangaLibraryItem): Boolean {
    val manga = item.libraryManga.manga

    val match = LibraryField.entries.any { field ->
        if (field.fieldOnly) return@any false

        when (field) {
            LibraryField.TITLE -> manga.title.contains(value, ignoreCase = true)
            LibraryField.AUTHOR -> manga.author?.contains(value, ignoreCase = true) ?: false
            LibraryField.ARTIST -> manga.artist?.contains(value, ignoreCase = true) ?: false
            LibraryField.DESCRIPTION -> manga.description?.contains(value, ignoreCase = true) ?: false
            LibraryField.GENRE -> manga.genre?.any { it.contains(value, ignoreCase = true) } ?: false
            LibraryField.SOURCE -> {
                item.sourceName.contains(value, ignoreCase = true) ||
                    (value.equals("local", ignoreCase = true) && manga.source == LocalMangaSource.ID)
            }

            LibraryField.LANGUAGE, LibraryField.SOURCE_ID -> error("How did we get here?")
        }
    }
    return if (negated) !match else match
}

private fun FieldQueryNode.matches(item: MangaLibraryItem): Boolean {
    val manga = item.libraryManga.manga

    val match = when (field) {
        LibraryField.GENRE -> {
            if (value.isEmpty()) {
                manga.genre.isNullOrEmpty()
            } else {
                manga.genre?.any { it.contains(value, ignoreCase = true) } ?: false
            }
        }

        LibraryField.SOURCE -> {
            if (value.isEmpty()) {
                item.sourceName.isEmpty()
            } else {
                item.sourceName.contains(value, ignoreCase = true) ||
                    (value.equals("local", ignoreCase = true) && manga.source == LocalMangaSource.ID)
            }
        }

        LibraryField.SOURCE_ID -> {
            value.toLongOrNull()?.let { it == manga.source } ?: false
        }

        else -> {
            val text = when (field) {
                LibraryField.TITLE -> manga.title
                LibraryField.AUTHOR -> manga.author
                LibraryField.ARTIST -> manga.artist
                LibraryField.DESCRIPTION -> manga.description
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

private fun ComparisonQueryNode.matches(item: MangaLibraryItem): Boolean {
    val manga = item.libraryManga.manga

    fun compareDates(date: Instant, value: String): Boolean? {
        val inputDate = runCatching { LocalDate.parse(value) }.getOrNull() ?: return null
        val mangaDate = date.atZone(ZoneId.systemDefault()).toLocalDate()
        return queryComparator.apply(mangaDate, inputDate)
    }

    val match = when (field) {
        ComparisonField.ID -> value.toLongOrNull()?.let { queryComparator.apply(manga.id, it) }

        ComparisonField.DATE_ADDED -> manga.dateAdded.takeIf {
            it > 0
        }?.let { compareDates(Instant.ofEpochMilli(it), value) }

        ComparisonField.FETCH_INTERVAL -> value.toIntOrNull()
            ?.let { queryComparator.apply(abs(manga.fetchInterval), it) }

        ComparisonField.NEXT_UPDATE -> manga.expectedNextUpdate?.takeIf {
            it.toEpochMilli() > 0
        }?.let { compareDates(it, value) }

        ComparisonField.UNREAD -> {
            value.toLongOrNull()?.let {
                queryComparator.apply(item.libraryManga.unreadCount, it)
            }
        }

        ComparisonField.READ -> {
            value.toLongOrNull()?.let {
                queryComparator.apply(item.libraryManga.readCount, it)
            }
        }

        ComparisonField.TOTAL -> {
            value.toLongOrNull()?.let {
                queryComparator.apply(item.libraryManga.totalChapters, it)
            }
        }
    } ?: false

    return if (negated) !match else match
}
