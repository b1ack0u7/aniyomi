package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import tachiyomi.data.handlers.manga.MangaDatabaseHandler
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.library.service.LibraryPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MangaCategoriesRestorer(
    private val mangaHandler: MangaDatabaseHandler = Injekt.get(),
    private val getMangaCategories: GetMangaCategories = Injekt.get(),
    private val libraryPreferences: LibraryPreferences = Injekt.get(),
) {

    suspend operator fun invoke(backupCategories: List<BackupCategory>) {
        if (backupCategories.isNotEmpty()) {
            val dbCategories = getMangaCategories.await()
            val dbCategoriesByName = dbCategories.associateBy { it.name }.toMutableMap()
            var nextOrder = dbCategories.maxOfOrNull { it.order }?.plus(1) ?: 0

            val categories = backupCategories
                .sortedBy { it.order }
                .map {
                    val dbCategory = dbCategoriesByName[it.name]
                    if (dbCategory != null) {
                        val hidden = it.hidden ?: return@map dbCategory
                        mangaHandler.await {
                            categoryQueries.update(null, null, null, if (hidden) 1L else 0L, dbCategory.id)
                        }
                        return@map dbCategory.copy(hidden = hidden).also { category ->
                            dbCategoriesByName[category.name] = category
                        }
                    }
                    val order = nextOrder++
                    mangaHandler.await(inTransaction = true) {
                        categoryQueries.insert(it.name, order, it.flags)
                        val id = categoryQueries.selectLastInsertedRowId().executeAsOne()
                        it.hidden?.let { hidden ->
                            categoryQueries.update(null, null, null, if (hidden) 1L else 0L, id)
                        }
                        id
                    }
                        .let { id -> it.toCategory(id).copy(order = order) }
                        .also { category -> dbCategoriesByName[category.name] = category }
                }

            libraryPreferences.categorizedDisplaySettings().set(
                (dbCategories + categories)
                    .distinctBy { it.flags }
                    .size > 1,
            )
        }
    }
}
