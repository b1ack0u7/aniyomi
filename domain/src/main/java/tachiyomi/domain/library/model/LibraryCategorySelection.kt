package tachiyomi.domain.library.model

fun <T> List<T>.filterByLibraryCategories(
    included: Collection<Long>,
    excluded: Collection<Long>,
    categories: (T) -> Collection<Long>,
): List<T> {
    val includedIds = included.toSet()
    val excludedIds = excluded.toSet()
    return filter { item ->
        val itemCategories = categories(item)
        (includedIds.isEmpty() || itemCategories.any { it in includedIds }) &&
            itemCategories.none { it in excludedIds }
    }
}
