package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.History
import eu.kanade.tachiyomi.data.backup.BackupReader
import eu.kanade.tachiyomi.data.backup.BackupWriter
import eu.kanade.tachiyomi.data.backup.create.creators.AnimeCategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.MangaCategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.LegacyBackup
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okio.Buffer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.core.common.preference.Preference
import tachiyomi.data.AnimeUpdateStrategyColumnAdapter
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.FetchTypeColumnAdapter
import tachiyomi.data.MangaUpdateStrategyColumnAdapter
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.category.anime.AnimeCategoryRepositoryImpl
import tachiyomi.data.category.manga.MangaCategoryRepositoryImpl
import tachiyomi.data.handlers.anime.AndroidAnimeDatabaseHandler
import tachiyomi.data.handlers.manga.AndroidMangaDatabaseHandler
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.mi.data.AnimeDatabase
import data.Chapter as SqlChapter
import data.Manga as SqlManga
import dataanime.Anime as SqlAnime
import dataanime.Anime_history as SqlAnimeHistory

class CategoryHiddenRestoreTest {

    @ParameterizedTest
    @MethodSource("restoreCases")
    fun `hidden roundtrips through metadata and repeated restore preserves legacy absence`(case: RestoreCase) =
        runBlocking<Unit> {
            case.stack.fixture().use { fixture ->
                case.existingHidden?.let { fixture.seed(hidden = it) }
                val category = BackupCategory("Category", order = 91, id = 71, flags = 7, hidden = case.hidden)
                val categories = roundtrip(category, case.stack, case.legacy)
                categories.single().hidden shouldBe case.hidden

                repeat(2) {
                    fixture.restore(categories)
                    val actual = fixture.categories().filterNot(Category::isSystemCategory).single()
                    actual.hidden shouldBe (case.hidden ?: case.existingHidden ?: false)
                    actual.order shouldBe if (case.existingHidden == null) 0L else 12L
                    actual.flags shouldBe if (case.existingHidden == null) 7L else 23L
                    actual.name shouldBe "Category"
                }
            }
        }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `category creators export explicit visibility for both hidden and visible categories`(stack: Stack) =
        runBlocking<Unit> {
            stack.fixture().use { fixture ->
                fixture.seed(hidden = true)
                fixture.seed(name = "Visible", hidden = false)
                val categories = fixture.create()
                categories.map { it.name to it.hidden }.toMap() shouldBe mapOf("Category" to true, "Visible" to false)
                categories.none { it.id == Category.UNCATEGORIZED_ID } shouldBe true
                categories.forEach { category ->
                    roundtrip(category, stack, legacy = false).single().hidden shouldBe category.hidden
                }
            }
        }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `duplicate category names keep one row and absent copies do not unhide it`(stack: Stack) = runBlocking<Unit> {
        stack.fixture().use { fixture ->
            val categories = listOf(
                BackupCategory("Category", order = 1, hidden = true),
                BackupCategory("Category", order = 2),
            )
            repeat(2) {
                fixture.restore(categories)
                fixture.categories().filterNot(Category::isSystemCategory).single().hidden shouldBe true
            }
            fixture.restore(listOf(BackupCategory("Category", hidden = false)))
            fixture.categories().filterNot(Category::isSystemCategory).single().hidden shouldBe false
        }
    }

    @ParameterizedTest
    @EnumSource(Stack::class)
    fun `empty category restore preserves local visibility and unrelated categories`(stack: Stack) = runBlocking<Unit> {
        stack.fixture().use { fixture ->
            fixture.seed(hidden = true)
            val before = fixture.categories()
            fixture.restore(emptyList())
            fixture.categories() shouldBe before
        }
    }

    @ParameterizedTest
    @MethodSource("visibilityCases")
    fun `old category readers skip new visibility and old writers preserve absent state`(hidden: Boolean?) {
        val old = OldBackupCategory("Category", order = 91, id = 71, flags = 7)
        val oldBytes = ProtoBuf.encodeToByteArray(OldBackupCategory.serializer(), old)
        ProtoBuf.decodeFromByteArray(BackupCategory.serializer(), oldBytes).hidden shouldBe null
        val current = BackupCategory("Category", order = 91, id = 71, flags = 7, hidden = hidden)
        val bytes = ProtoBuf.encodeToByteArray(BackupCategory.serializer(), current)
        ProtoBuf.decodeFromByteArray(OldBackupCategory.serializer(), bytes) shouldBe old
        if (hidden == null) bytes.toList() shouldBe oldBytes.toList()
    }

    private fun roundtrip(category: BackupCategory, stack: Stack, legacy: Boolean): List<BackupCategory> {
        val manga = listOf(category).takeIf { stack == Stack.MANGA }.orEmpty()
        val anime = listOf(category).takeIf { stack == Stack.ANIME }.orEmpty()
        val buffer = Buffer()
        if (legacy) {
            buffer.write(
                ProtoBuf.encodeToByteArray(
                    LegacyBackup.serializer(),
                    LegacyBackup(backupCategories = manga, backupAnimeCategories = anime),
                ),
            )
        } else {
            BackupWriter(buffer, ProtoBuf).writeMetadata(
                Backup(backupCategories = manga, backupAnimeCategories = anime),
            )
        }
        return BackupReader(buffer, ProtoBuf, legacy).use { reader ->
            reader.nextEntry() shouldBe null
            val metadata = reader.readMetadata()
            when (stack) {
                Stack.MANGA -> metadata.backupCategories
                Stack.ANIME -> metadata.backupAnimeCategories
            }
        }
    }

    enum class Stack {
        MANGA,
        ANIME,
        ;

        fun fixture(): Fixture = when (this) {
            MANGA -> MangaFixture()
            ANIME -> AnimeFixture()
        }
    }

    data class RestoreCase(val stack: Stack, val legacy: Boolean, val hidden: Boolean?, val existingHidden: Boolean?)

    sealed interface Fixture : AutoCloseable {
        fun seed(name: String = "Category", hidden: Boolean)
        suspend fun categories(): List<Category>
        suspend fun restore(categories: List<BackupCategory>)
        suspend fun create(): List<BackupCategory>
    }

    private class MangaFixture : Fixture {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val db = Database(
            driver,
            chapterAdapter = SqlChapter.Adapter(MemoColumnAdapter),
            historyAdapter = History.Adapter(DateColumnAdapter),
            mangaAdapter = SqlManga.Adapter(
                StringListColumnAdapter,
                MangaUpdateStrategyColumnAdapter,
                MemoColumnAdapter,
            ),
        )
        private val handler = AndroidMangaDatabaseHandler(db, driver)
        private val getCategories = GetMangaCategories(MangaCategoryRepositoryImpl(handler))
        private val restorer = MangaCategoriesRestorer(handler, getCategories, libraryPreferences())

        init {
            Database.Schema.create(driver)
        }

        override fun seed(name: String, hidden: Boolean) {
            db.categoryQueries.insert(name, 12, 23)
            val id = db.categoryQueries.selectLastInsertedRowId().executeAsOne()
            db.categoryQueries.update(null, null, null, if (hidden) 1 else 0, id)
        }

        override suspend fun categories() = getCategories.await()
        override suspend fun restore(categories: List<BackupCategory>) = restorer(categories)
        override suspend fun create() = MangaCategoriesBackupCreator(getCategories)()
        override fun close() = driver.close()
    }

    private class AnimeFixture : Fixture {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        private val db = AnimeDatabase(
            driver,
            anime_historyAdapter = SqlAnimeHistory.Adapter(DateColumnAdapter),
            animeAdapter = SqlAnime.Adapter(
                StringListColumnAdapter,
                AnimeUpdateStrategyColumnAdapter,
                FetchTypeColumnAdapter,
            ),
        )
        private val handler = AndroidAnimeDatabaseHandler(db, driver)
        private val getCategories = GetAnimeCategories(AnimeCategoryRepositoryImpl(handler))
        private val restorer = AnimeCategoriesRestorer(handler, getCategories, libraryPreferences())

        init {
            AnimeDatabase.Schema.create(driver)
        }

        override fun seed(name: String, hidden: Boolean) {
            db.categoryQueries.insert(name, 12, 23)
            val id = db.categoryQueries.selectLastInsertedRowId().executeAsOne()
            db.categoryQueries.update(null, null, null, if (hidden) 1 else 0, id)
        }

        override suspend fun categories() = getCategories.await()
        override suspend fun restore(categories: List<BackupCategory>) = restorer(categories)
        override suspend fun create() = AnimeCategoriesBackupCreator(getCategories)()
        override fun close() = driver.close()
    }

    @Serializable
    private data class OldBackupCategory(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val order: Long = 0,
        @ProtoNumber(3) val id: Long = 0,
        @ProtoNumber(100) val flags: Long = 0,
    )

    companion object {
        @JvmStatic
        fun restoreCases() = Stack.entries.flatMap { stack ->
            listOf(false, true).flatMap { legacy ->
                listOf(null, false, true).flatMap { hidden ->
                    listOf(null, false, true).map { existingHidden ->
                        RestoreCase(stack, legacy, hidden, existingHidden)
                    }
                }
            }
        }.stream()

        @JvmStatic
        fun visibilityCases() = listOf(null, false, true).stream()

        private fun libraryPreferences(): LibraryPreferences = mockk {
            every { categorizedDisplaySettings() } returns mockk<Preference<Boolean>>(relaxed = true)
        }
    }
}
