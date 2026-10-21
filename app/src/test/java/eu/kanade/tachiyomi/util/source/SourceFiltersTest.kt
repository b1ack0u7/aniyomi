package eu.kanade.tachiyomi.util.source

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Test

class SourceFiltersTest {

    @Test
    fun `manga applied filters preserve extension subclasses without sharing draft state`() {
        val draft = mangaFilters()
        val genre = (draft[0] as MangaGenres).state.single() as MangaGenre
        genre.state = true
        (draft[1] as MangaStatus).state = Filter.TriState.STATE_EXCLUDE
        (draft[2] as MangaQuery).state = "query"
        (draft[3] as MangaType).state = 1
        (draft[4] as MangaSort).state = Filter.Sort.Selection(1, false)

        val applied = mangaFilters().copyStatesFrom(draft)

        assertEquals(listOf("Action"), (applied[0] as MangaGenres).selectedGenres())
        assertEquals(Filter.TriState.STATE_EXCLUDE, applied[1].state)
        assertEquals("query", applied[2].state)
        assertEquals(1, applied[3].state)
        assertEquals(Filter.Sort.Selection(1, false), applied[4].state)
        assertNotSame(draft[0], applied[0])
        assertNotSame(genre, (applied[0] as MangaGenres).state.single())
        genre.state = false
        (draft[4] as MangaSort).select(0)
        assertEquals(listOf("Action"), (applied[0] as MangaGenres).selectedGenres())
        assertEquals(Filter.Sort.Selection(1, false), applied[4].state)
    }

    @Test
    fun `anime applied filters preserve extension subclasses without sharing draft state`() {
        val draft = animeFilters()
        val genre = (draft[0] as AnimeGenres).state.single() as AnimeGenre
        genre.state = true
        (draft[1] as AnimeStatus).state = AnimeFilter.TriState.STATE_EXCLUDE
        (draft[2] as AnimeQuery).state = "query"
        (draft[3] as AnimeType).state = 1
        (draft[4] as AnimeSort).state = AnimeFilter.Sort.Selection(1, false)

        val applied = animeFilters().copyStatesFrom(draft)

        assertEquals(listOf("Action"), (applied[0] as AnimeGenres).selectedGenres())
        assertEquals(AnimeFilter.TriState.STATE_EXCLUDE, applied[1].state)
        assertEquals("query", applied[2].state)
        assertEquals(1, applied[3].state)
        assertEquals(AnimeFilter.Sort.Selection(1, false), applied[4].state)
        assertNotSame(draft[0], applied[0])
        assertNotSame(genre, (applied[0] as AnimeGenres).state.single())
        genre.state = false
        (draft[4] as AnimeSort).select(0)
        assertEquals(listOf("Action"), (applied[0] as AnimeGenres).selectedGenres())
        assertEquals(AnimeFilter.Sort.Selection(1, false), applied[4].state)
    }

    @Test
    fun `nested manga and anime groups retain independent child state`() {
        val mangaDraft = MangaOuterGenres()
        mangaDraft.state.single().state.single().state = true
        val mangaApplied = FilterList(MangaOuterGenres()).copyStatesFrom(FilterList(mangaDraft))
        mangaDraft.state.single().state.single().state = false
        assertEquals(listOf("Action"), (mangaApplied.single() as MangaOuterGenres).state.single().selectedGenres())

        val animeDraft = AnimeOuterGenres()
        animeDraft.state.single().state.single().state = true
        val animeApplied = AnimeFilterList(AnimeOuterGenres()).copyStatesFrom(AnimeFilterList(animeDraft))
        animeDraft.state.single().state.single().state = false
        assertEquals(listOf("Action"), (animeApplied.single() as AnimeOuterGenres).state.single().selectedGenres())
    }

    @Test
    fun `manga sorting supports no initial selection and preserves direction when changing columns`() {
        val sort = MangaSort()
        sort.select(1)
        assertEquals(Filter.Sort.Selection(1, true), sort.state)
        sort.select(1)
        assertEquals(Filter.Sort.Selection(1, false), sort.state)
        sort.select(0)
        assertEquals(Filter.Sort.Selection(0, false), sort.state)
    }

    @Test
    fun `anime sorting supports no initial selection and preserves direction when changing columns`() {
        val sort = AnimeSort()
        sort.select(1)
        assertEquals(AnimeFilter.Sort.Selection(1, true), sort.state)
        sort.select(1)
        assertEquals(AnimeFilter.Sort.Selection(1, false), sort.state)
        sort.select(0)
        assertEquals(AnimeFilter.Sort.Selection(0, false), sort.state)
    }

    @Test
    fun `changed extension filter schemas keep defaults for incompatible entries`() {
        val previousManga = FilterList(MangaQuery().apply { state = "old" })
        val currentManga = FilterList(MangaStatus()).copyStatesFrom(previousManga)
        assertEquals(Filter.TriState.STATE_IGNORE, currentManga.single().state)
        val previousAnime = AnimeFilterList(AnimeQuery().apply { state = "old" })
        val currentAnime = AnimeFilterList(AnimeStatus()).copyStatesFrom(previousAnime)
        assertEquals(AnimeFilter.TriState.STATE_IGNORE, currentAnime.single().state)
    }

    private fun mangaFilters() = FilterList(MangaGenres(), MangaStatus(), MangaQuery(), MangaType(), MangaSort())
    private fun animeFilters() = AnimeFilterList(AnimeGenres(), AnimeStatus(), AnimeQuery(), AnimeType(), AnimeSort())

    private class MangaGenre : Filter.CheckBox("Action")
    private class MangaGenres : Filter.Group<MangaGenre>("Genres", listOf(MangaGenre())) {
        fun selectedGenres() = state.filter { it.state }.map { it.name }
    }
    private class MangaOuterGenres : Filter.Group<MangaGenres>("Outer", listOf(MangaGenres()))
    private class MangaStatus : Filter.TriState("Status")
    private class MangaQuery : Filter.Text("Query")
    private class MangaType : Filter.Select<String>("Type", arrayOf("All", "Manga"))
    private class MangaSort : Filter.Sort("Sort", arrayOf("Name", "Date"))

    private class AnimeGenre : AnimeFilter.CheckBox("Action")
    private class AnimeGenres : AnimeFilter.Group<AnimeGenre>("Genres", listOf(AnimeGenre())) {
        fun selectedGenres() = state.filter { it.state }.map { it.name }
    }
    private class AnimeOuterGenres : AnimeFilter.Group<AnimeGenres>("Outer", listOf(AnimeGenres()))
    private class AnimeStatus : AnimeFilter.TriState("Status")
    private class AnimeQuery : AnimeFilter.Text("Query")
    private class AnimeType : AnimeFilter.Select<String>("Type", arrayOf("All", "Anime"))
    private class AnimeSort : AnimeFilter.Sort("Sort", arrayOf("Name", "Date"))
}
