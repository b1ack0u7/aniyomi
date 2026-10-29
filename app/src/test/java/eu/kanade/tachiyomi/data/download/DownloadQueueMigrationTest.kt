package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.SharedPreferences
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.History
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.data.download.anime.AnimeDownloadStore
import eu.kanade.tachiyomi.data.download.anime.model.AnimeDownload
import eu.kanade.tachiyomi.data.download.manga.MangaDownloadStore
import eu.kanade.tachiyomi.data.download.manga.model.MangaDownload
import eu.kanade.tachiyomi.source.online.HttpSource
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import tachiyomi.data.AnimeUpdateStrategyColumnAdapter
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.FetchTypeColumnAdapter
import tachiyomi.data.MangaUpdateStrategyColumnAdapter
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.entries.anime.AnimeRepositoryImpl
import tachiyomi.data.entries.manga.MangaRepositoryImpl
import tachiyomi.data.handlers.anime.AndroidAnimeDatabaseHandler
import tachiyomi.data.handlers.manga.AndroidMangaDatabaseHandler
import tachiyomi.data.items.chapter.ChapterRepositoryImpl
import tachiyomi.data.items.episode.EpisodeRepositoryImpl
import tachiyomi.domain.entries.anime.interactor.GetAnime
import tachiyomi.domain.entries.anime.model.Anime
import tachiyomi.domain.entries.manga.interactor.GetManga
import tachiyomi.domain.entries.manga.model.Manga
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.chapter.model.Chapter
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.episode.model.Episode
import tachiyomi.domain.source.anime.service.AnimeSourceManager
import tachiyomi.domain.source.manga.service.MangaSourceManager
import tachiyomi.mi.data.AnimeDatabase
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import data.Chapter as SqlChapter
import data.Manga as SqlManga
import dataanime.Anime as SqlAnime
import dataanime.Anime_history as SqlAnimeHistory

class DownloadQueueMigrationTest {

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `old parent and child ids resolve to one durable canonical queue entry`(stack: String) = runBlocking<Unit> {
        Fixture(stack).use { f ->
            f.alias(2, 2, 1)
            f.legacy(2, 2, 4)

            f.restore().map { it.id } shouldBe listOf(1L)
            f.values.keys shouldBe setOf(f.key(1))
            f.record(f.key(1))["identityVersion"]?.jsonPrimitive?.content shouldBe "1"
            f.record(f.key(1))["order"]?.jsonPrimitive?.content shouldBe "4"
            f.restore().map { it.id } shouldBe listOf(1L)
            f.values.keys shouldBe setOf(f.key(1))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `duplicate old and canonical queue records retain earliest order and failure status`(stack: String) =
        runBlocking<Unit> {
            Fixture(stack).use { f ->
                f.alias(1, 2, 1)
                f.legacy(1, 2, 3)
                f.canonical(1, 1, 8, failed = true)

                val restored = f.restore()
                restored.map { it.id } shouldBe listOf(1L)
                if (stack == "manga") restored.single().failed shouldBe true
                f.values.keys shouldBe setOf(f.key(1))
                f.record(f.key(1))["order"]?.jsonPrimitive?.content shouldBe "3"
                if (stack == "manga") f.restore().single().failed shouldBe true
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `missing sources and temporarily failed lookups leave legacy records available for retry`(stack: String) =
        runBlocking<Unit> {
            Fixture(stack).use { f ->
                f.alias(2, 2, 1)
                f.legacy(2, 2, 7)
                val original = f.values.toMap()
                f.sourceAvailable = false

                f.restore() shouldBe emptyList()
                f.values shouldBe original
                f.sourceAvailable = true
                f.failDatabase = true
                f.restore() shouldBe emptyList()
                f.values shouldBe original
                f.failDatabase = false

                f.restore().map { it.id } shouldBe listOf(1L)
                f.values.keys shouldBe setOf(f.key(1))
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `deleted targets never retarget old queue entries to reused ids`(stack: String) = runBlocking<Unit> {
        Fixture(stack).use { f ->
            f.alias(1, 2, 1)
            f.legacy(1, 2, 0)
            f.execute("DELETE FROM ${f.itemTable} WHERE id = 1")
            f.item(1, "/unrelated")
            f.canonical(1, 1, 1)

            f.restore().map { it.id } shouldBe listOf(1L)
            f.values.keys shouldBe setOf("2", f.key(1))
            f.execute("DELETE FROM ${f.itemTable} WHERE id = 1")
            f.restore() shouldBe emptyList()
            f.values.keys shouldBe setOf("2", f.key(1))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `new canonical records do not follow aliases for reused old ids`(stack: String) = runBlocking<Unit> {
        Fixture(stack).use { f ->
            f.item(3, "/other")
            f.alias(1, 1, 3)
            f.canonical(1, 1, 2)

            f.restore().map { it.id } shouldBe listOf(1L)
            f.values.keys shouldBe setOf(f.key(1))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `clear while restore is suspended never resurrects the old snapshot`(stack: String) = runBlocking<Unit> {
        Fixture(stack).use { f ->
            f.alias(1, 2, 1)
            f.legacy(1, 2, 0)
            val gate = CompletableDeferred<Unit>()
            f.barrier = gate
            val pending = async(start = CoroutineStart.UNDISPATCHED) { f.restore() }

            f.clearQueue()
            gate.complete(Unit)

            pending.await() shouldBe emptyList()
            f.values shouldBe emptyMap()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `remove while restore is suspended never readds the canonical download`(stack: String) = runBlocking<Unit> {
        Fixture(stack).use { f ->
            f.alias(1, 2, 1)
            f.legacy(1, 2, 1)
            f.canonical(1, 1, 0)
            val gate = CompletableDeferred<Unit>()
            f.barrier = gate
            val pending = async(start = CoroutineStart.UNDISPATCHED) { f.restore() }

            f.removeQueue()
            gate.complete(Unit)

            pending.await() shouldBe emptyList()
            f.values shouldBe emptyMap()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["manga", "anime"])
    fun `changed canonical entry is never overwritten by an older alias snapshot`(stack: String) = runBlocking<Unit> {
        Fixture(stack).use { f ->
            f.alias(1, 2, 1)
            f.legacy(1, 2, 0)
            f.canonical(1, 1, 2)
            val gate = CompletableDeferred<Unit>()
            f.barrier = gate
            val pending = async(start = CoroutineStart.UNDISPATCHED) { f.restore() }

            f.canonical(1, 1, 9)
            val replacement = f.values.getValue(f.key(1))
            gate.complete(Unit)

            pending.await() shouldBe emptyList()
            f.values shouldBe mapOf(f.key(1) to replacement)
        }
    }

    @Test
    fun `manga and anime share preferences without overwriting or clearing each other`() = runBlocking<Unit> {
        val shared = linkedMapOf<String, String>()
        Fixture("manga", shared).use { manga ->
            Fixture("anime", shared).use { anime ->
                manga.canonical(1, 1, 0)
                anime.canonical(1, 1, 0)

                manga.restore().map { it.id } shouldBe listOf(1L)
                anime.restore().map { it.id } shouldBe listOf(1L)
                shared.keys shouldBe setOf(manga.key(1), anime.key(1))
                manga.clearQueue()
                shared.keys shouldBe setOf(anime.key(1))
                anime.restore().map { it.id } shouldBe listOf(1L)
            }
        }
    }

    @ParameterizedTest
    @CsvSource("manga, true", "manga, false", "anime, true", "anime, false")
    fun `queue publication and cancellation form one handoff without resurrecting downloads`(
        stack: String,
        clear: Boolean,
    ) = runBlocking<Unit> {
        Fixture(stack).use { f ->
            f.canonical(1, 1, 0)
            val queue = MutableStateFlow(emptyList<Restored>())
            val publishing = CountDownLatch(1)
            val release = CountDownLatch(1)
            val cancelling = CountDownLatch(1)
            val restored = async(Dispatchers.Default) {
                f.restore { downloads ->
                    publishing.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    queue.update { it + downloads }
                }
            }
            try {
                check(publishing.await(5, TimeUnit.SECONDS))
                val cancelled = async(Dispatchers.Default) {
                    queue.update { previous ->
                        cancelling.countDown()
                        if (clear) {
                            f.clearQueue()
                            emptyList()
                        } else {
                            f.removeQueue()
                            previous.filterNot { it.id == 1L }
                        }
                    }
                }
                check(cancelling.await(5, TimeUnit.SECONDS))
                cancelled.isCompleted shouldBe false
                release.countDown()
                restored.await()
                cancelled.await()

                queue.value shouldBe emptyList()
                f.values shouldBe emptyMap()
            } finally {
                release.countDown()
            }
        }
    }

    private data class Restored(val id: Long, val failed: Boolean)

    private class Fixture(
        val stack: String,
        val values: LinkedHashMap<String, String> = linkedMapOf(),
    ) : AutoCloseable {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val itemTable = if (stack == "manga") "chapter" else "episode"
        private val preferences = mockk<SharedPreferences>()
        private val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        private val context = mockk<Context>()
        private val mutations = linkedMapOf<String, String?>()
        private var clearing = false
        var barrier: CompletableDeferred<Unit>? = null
        lateinit var clearQueue: () -> Unit
        lateinit var removeQueue: () -> Unit
        var sourceAvailable = true
        var failDatabase = false
        private val restoreQueue: suspend ((List<Restored>) -> Unit) -> List<Restored>

        init {
            every { context.getSharedPreferences("active_downloads", Context.MODE_PRIVATE) } returns preferences
            every { preferences.all } answers { values.toMap() }
            every { preferences.getString(any(), any()) } answers { values[firstArg()] ?: secondArg() }
            every { preferences.edit() } answers {
                mutations.clear()
                clearing = false
                editor
            }
            every { editor.putString(any(), any()) } answers {
                mutations[firstArg()] = secondArg()
                editor
            }
            every { editor.remove(any()) } answers {
                mutations[firstArg()] = null
                editor
            }
            every { editor.clear() } answers {
                clearing = true
                editor
            }
            every { editor.commit() } answers {
                applyMutations()
                true
            }
            every { editor.apply() } answers { applyMutations() }
            execute("PRAGMA foreign_keys = ON")
            if (stack == "manga") {
                val db = Database(
                    driver,
                    chapterAdapter = SqlChapter.Adapter(MemoColumnAdapter),
                    historyAdapter = History.Adapter(DateColumnAdapter),
                    mangaAdapter = SqlManga.Adapter(
                        StringListColumnAdapter,
                        MangaUpdateStrategyColumnAdapter,
                        MemoColumnAdapter,
                    ),
                )
                Database.Schema.create(driver)
                val handler =
                    AndroidMangaDatabaseHandler(db, driver, awaitReady = {
                        barrier?.await()
                        check(!failDatabase)
                    })
                val getParent = GetManga(MangaRepositoryImpl(handler))
                val getItems = GetChaptersByMangaId(ChapterRepositoryImpl(handler))
                val sources = mockk<MangaSourceManager>()
                val source = mockk<HttpSource>()
                every { sources.get(3) } answers { source.takeIf { sourceAvailable } }
                restoreQueue = { publish ->
                    val store = MangaDownloadStore(
                        context,
                        sources,
                        Json,
                        getParent,
                        getItems,
                        DownloadQueueMigration(mangaHandler = handler),
                    )
                    clearQueue = store::clear
                    removeQueue = {
                        store.remove(MangaDownload(source, Manga.create().copy(id = 1), Chapter.create().copy(id = 1)))
                    }
                    store.restore { downloads ->
                        publish(downloads.map { Restored(it.chapter.id, it.status == MangaDownload.State.ERROR) })
                    }.map { Restored(it.chapter.id, it.status == MangaDownload.State.ERROR) }
                }
            } else {
                val db = AnimeDatabase(
                    driver,
                    anime_historyAdapter = SqlAnimeHistory.Adapter(DateColumnAdapter),
                    animeAdapter = SqlAnime.Adapter(
                        StringListColumnAdapter,
                        AnimeUpdateStrategyColumnAdapter,
                        FetchTypeColumnAdapter,
                    ),
                )
                AnimeDatabase.Schema.create(driver)
                val handler =
                    AndroidAnimeDatabaseHandler(db, driver, awaitReady = {
                        barrier?.await()
                        check(!failDatabase)
                    })
                val getParent = GetAnime(AnimeRepositoryImpl(handler))
                val getItems = GetEpisodesByAnimeId(EpisodeRepositoryImpl(handler))
                val sources = mockk<AnimeSourceManager>()
                val source = mockk<AnimeHttpSource>()
                every { sources.get(3) } answers { source.takeIf { sourceAvailable } }
                restoreQueue = { publish ->
                    val store = AnimeDownloadStore(
                        context,
                        sources,
                        Json,
                        getParent,
                        getItems,
                        DownloadQueueMigration(animeHandler = handler),
                    )
                    clearQueue = store::clear
                    removeQueue = {
                        store.remove(AnimeDownload(source, Anime.create().copy(id = 1), Episode.create().copy(id = 1)))
                    }
                    store.restore { downloads ->
                        publish(downloads.map { Restored(it.episode.id, it.status == AnimeDownload.State.ERROR) })
                    }.map { Restored(it.episode.id, it.status == AnimeDownload.State.ERROR) }
                }
            }
            seed(stack, mapOf("id" to 1, "source_id" to 3, "remote_url" to "/title", "remote_title" to "Title"))
            item(1, "/item")
        }

        private fun applyMutations() {
            if (clearing) values.clear()
            mutations.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
            mutations.clear()
        }

        fun key(itemId: Long) = "v1:$stack:$itemId"

        fun legacy(parentId: Long, itemId: Long, order: Int) {
            values[itemId.toString()] = """{"${stack}Id":$parentId,"${itemTable}Id":$itemId,"order":$order}"""
        }

        fun canonical(parentId: Long, itemId: Long, order: Int, failed: Boolean = false) {
            val failure = if (stack == "manga") ",\"failed\":$failed" else ""
            values[key(itemId)] =
                """{"${stack}Id":$parentId,"${itemTable}Id":$itemId,"order":$order,"identityVersion":1$failure}"""
        }

        fun alias(parentId: Long, itemId: Long, canonicalId: Long) {
            execute("INSERT INTO download_queue_alias VALUES ($parentId, $itemId, $canonicalId)")
        }

        fun item(id: Long, url: String) {
            seed(itemTable, mapOf("id" to id, "${stack}_id" to 1, "remote_url" to url, "remote_name" to "Item"))
        }

        suspend fun restore(publish: (List<Restored>) -> Unit = {}) = restoreQueue(publish)

        fun record(key: String) = Json.parseToJsonElement(values.getValue(key)).jsonObject

        fun execute(sql: String) {
            driver.execute(null, sql, 0)
        }

        private fun seed(table: String, values: Map<String, Any>) {
            val defaults = driver.executeQuery(null, "PRAGMA table_info($table)", { cursor ->
                val result = linkedMapOf<String, Any>()
                while (cursor.next().value) {
                    if (cursor.getLong(3) == 1L && cursor.getString(4) == null && cursor.getLong(5) == 0L) {
                        result[cursor.getString(1)!!] = if (cursor.getString(2) == "TEXT") "" else 0
                    }
                }
                QueryResult.Value(result)
            }, 0).value
            defaults.putAll(values)
            val encoded = defaults.values.joinToString { value ->
                if (value is String) "'${value.replace("'", "''")}'" else value.toString()
            }
            execute("INSERT INTO $table (${defaults.keys.joinToString()}) VALUES ($encoded)")
        }

        override fun close() = driver.close()
    }
}
