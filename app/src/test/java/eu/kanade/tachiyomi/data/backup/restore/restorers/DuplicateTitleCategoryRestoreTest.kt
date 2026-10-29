package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.BackupEntryType
import eu.kanade.tachiyomi.data.backup.BackupReader
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.LegacyBackup
import eu.kanade.tachiyomi.data.backup.restore.BackupRestoreStore
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.stream.Stream

class DuplicateTitleCategoryRestoreTest {

    @TempDir
    lateinit var directory: File

    @ParameterizedTest
    @MethodSource("categoryCases")
    fun `duplicate title categories are merged within the backup before any copy is restored`(
        stack: Stack,
        legacy: Boolean,
        reverse: Boolean,
        scenario: Scenario,
    ) = runBlocking<Unit> {
        stack.fixture().use { fixture ->
            fixture.seedCategories()
            val entries = when (scenario) {
                Scenario.FAVORITES -> listOf(Entry(categories = listOf(11)), Entry(categories = listOf(22)))
                Scenario.NONFAVORITES -> listOf(
                    Entry(favorite = false, categories = listOf(11)),
                    Entry(favorite = false, categories = listOf(22)),
                )
                Scenario.MIXED -> listOf(
                    Entry(favorite = false, categories = listOf(11)),
                    Entry(categories = listOf(22)),
                )
                Scenario.DEFAULT -> listOf(
                    Entry(favorite = false, categories = listOf(11)),
                    Entry(),
                )
            }.let { if (reverse) it.reversed() else it }

            repeat(2) {
                fixture.restore(entries, legacy)
                fixture.categories(1, "/title") shouldBe when (scenario) {
                    Scenario.FAVORITES, Scenario.NONFAVORITES -> listOf("One", "Two")
                    Scenario.MIXED -> listOf("Two")
                    Scenario.DEFAULT -> emptyList()
                }
            }
        }
    }

    @ParameterizedTest
    @MethodSource("stackFormats")
    fun `category unions do not cross sources titles local data or restore sessions`(
        stack: Stack,
        legacy: Boolean,
    ) = runBlocking<Unit> {
        stack.fixture().use { fixture ->
            fixture.seedCategories()
            fixture.restore(listOf(Entry(categories = listOf(33))), legacy)
            fixture.restore(
                listOf(
                    Entry(categories = listOf(11)),
                    Entry(categories = listOf(22)),
                    Entry(source = 2, categories = listOf(33)),
                    Entry(url = "/other", categories = listOf(33)),
                ),
                legacy,
            )
            fixture.categories(1, "/title") shouldBe listOf("One", "Two")
            fixture.categories(2, "/title") shouldBe listOf("Three")
            fixture.categories(1, "/other") shouldBe listOf("Three")

            fixture.restore(listOf(Entry(categories = listOf(22))), legacy)
            fixture.categories(1, "/title") shouldBe listOf("Two")
            fixture.restore(listOf(Entry()), legacy)
            fixture.categories(1, "/title") shouldBe listOf("Two")
            fixture.restore(listOf(Entry(categories = listOf(11))), legacy, restoreCategories = false)
            fixture.categories(1, "/title") shouldBe listOf("Two")
        }
    }

    private suspend fun Fixture.restore(entries: List<Entry>, legacy: Boolean, restoreCategories: Boolean = true) {
        val backup = when (this) {
            is MangaFixture -> Backup(
                backupManga = entries.map {
                    BackupManga(it.source, it.url, title = "Title", favorite = it.favorite, categories = it.categories)
                },
            )
            is AnimeFixture -> Backup(
                backupAnime = entries.map {
                    BackupAnime(it.source, it.url, title = "Title", favorite = it.favorite, categories = it.categories)
                },
            )
        }
        val bytes = if (legacy) {
            ProtoBuf.encodeToByteArray(
                LegacyBackup.serializer(),
                LegacyBackup(backupManga = backup.backupManga, backupAnime = backup.backupAnime),
            )
        } else {
            ProtoBuf.encodeToByteArray(Backup.serializer(), backup)
        }
        BackupRestoreStore(File.createTempFile("categories", ".tmp", directory), ProtoBuf).use { store ->
            BackupReader(Buffer().write(bytes), ProtoBuf, legacy).use { reader ->
                while (true) {
                    when (reader.nextEntry() ?: break) {
                        BackupEntryType.MANGA -> store.addManga(reader.readMangaEntry(), isExisting = false)
                        BackupEntryType.ANIME -> store.addAnime(reader.readAnimeEntry(), isExisting = false)
                    }
                }
            }
            val categories = CATEGORY_METADATA.takeIf { restoreCategories }.orEmpty()
            when (this) {
                is MangaFixture -> store.mangaEntries.forEach {
                    fixture.restorer.restore(store.readManga(it), categories)
                }
                is AnimeFixture -> store.animeEntries.forEach {
                    fixture.restorer.restore(store.readAnime(it), categories, null)
                }
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

    enum class Scenario { FAVORITES, NONFAVORITES, MIXED, DEFAULT }

    private data class Entry(
        val source: Long = 1,
        val url: String = "/title",
        val favorite: Boolean = true,
        val categories: List<Long> = emptyList(),
    )

    sealed interface Fixture : AutoCloseable {
        fun seedCategories()
        suspend fun categories(source: Long, url: String): List<String>
    }

    private class MangaFixture : Fixture {
        val fixture = DuplicateBackupRestoreTest.MangaFixture()

        override fun seedCategories() {
            CATEGORY_METADATA.forEach { fixture.db.categoryQueries.insert(it.name, it.order, it.flags) }
        }

        override suspend fun categories(source: Long, url: String): List<String> {
            val id = fixture.titles.getMangaByUrlAndSourceId(url, source)!!.id
            return fixture.categories.await(id).map { it.name }.sorted()
        }

        override fun close() = fixture.close()
    }

    private class AnimeFixture : Fixture {
        val fixture = DuplicateBackupRestoreTest.AnimeFixture()

        override fun seedCategories() {
            CATEGORY_METADATA.forEach { fixture.db.categoryQueries.insert(it.name, it.order, it.flags) }
        }

        override suspend fun categories(source: Long, url: String): List<String> {
            val id = fixture.titles.getAnimeByUrlAndSourceId(url, source)!!.id
            return fixture.categories.await(id).map { it.name }.sorted()
        }

        override fun close() = fixture.close()
    }

    companion object {
        private val CATEGORY_METADATA = listOf(
            BackupCategory("One", 11),
            BackupCategory("Two", 22),
            BackupCategory("Three", 33),
        )

        @JvmStatic
        fun categoryCases(): Stream<Arguments> = Stack.entries.flatMap { stack ->
            listOf(false, true).flatMap { legacy ->
                listOf(false, true).flatMap { reverse ->
                    Scenario.entries.map { Arguments.of(stack, legacy, reverse, it) }
                }
            }
        }.stream()

        @JvmStatic
        fun stackFormats(): Stream<Arguments> = Stack.entries.flatMap { stack ->
            listOf(false, true).map { Arguments.of(stack, it) }
        }.stream()
    }
}
