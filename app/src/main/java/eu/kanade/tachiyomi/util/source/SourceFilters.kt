package eu.kanade.tachiyomi.util.source

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList

internal fun FilterList.copyStatesFrom(filters: FilterList): FilterList = apply {
    zip(filters).forEach { (target, original) -> target.copyStateFrom(original) }
}

private fun Filter<*>.copyStateFrom(original: Filter<*>) {
    if (javaClass != original.javaClass || name != original.name) return

    when {
        this is Filter.Group<*> && original is Filter.Group<*> -> {
            state.zip(original.state).forEach { (target, previous) ->
                if (target is Filter<*> && previous is Filter<*>) target.copyStateFrom(previous)
            }
        }
        this is Filter.CheckBox && original is Filter.CheckBox -> state = original.state
        this is Filter.TriState && original is Filter.TriState -> state = original.state
        this is Filter.Text && original is Filter.Text -> state = original.state
        this is Filter.Select<*> && original is Filter.Select<*> -> state = original.state
        this is Filter.Sort && original is Filter.Sort -> state = original.state?.copy()
    }
}

internal fun AnimeFilterList.copyStatesFrom(filters: AnimeFilterList): AnimeFilterList = apply {
    zip(filters).forEach { (target, original) -> target.copyStateFrom(original) }
}

private fun AnimeFilter<*>.copyStateFrom(original: AnimeFilter<*>) {
    if (javaClass != original.javaClass || name != original.name) return

    when {
        this is AnimeFilter.Group<*> && original is AnimeFilter.Group<*> -> {
            state.zip(original.state).forEach { (target, previous) ->
                if (target is AnimeFilter<*> && previous is AnimeFilter<*>) target.copyStateFrom(previous)
            }
        }
        this is AnimeFilter.CheckBox && original is AnimeFilter.CheckBox -> state = original.state
        this is AnimeFilter.TriState && original is AnimeFilter.TriState -> state = original.state
        this is AnimeFilter.Text && original is AnimeFilter.Text -> state = original.state
        this is AnimeFilter.Select<*> && original is AnimeFilter.Select<*> -> state = original.state
        this is AnimeFilter.Sort && original is AnimeFilter.Sort -> state = original.state?.copy()
    }
}

internal fun Filter.Sort.select(index: Int) {
    val current = state
    state = Filter.Sort.Selection(
        index = index,
        ascending = if (current?.index == index) !current.ascending else current?.ascending ?: true,
    )
}

internal fun AnimeFilter.Sort.select(index: Int) {
    val current = state
    state = AnimeFilter.Sort.Selection(
        index = index,
        ascending = if (current?.index == index) !current.ascending else current?.ascending ?: true,
    )
}
