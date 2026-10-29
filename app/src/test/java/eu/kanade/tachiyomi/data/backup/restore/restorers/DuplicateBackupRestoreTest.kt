package eu.kanade.tachiyomi.data.backup.restore.restorers

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import data.History
import eu.kanade.domain.entries.anime.interactor.UpdateAnime
import eu.kanade.domain.entries.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.data.backup.BackupEntryType
import eu.kanade.tachiyomi.data.backup.BackupReader
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeHistory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.LegacyBackup
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tachiyomi.data.AnimeUpdateStrategyColumnAdapter
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.FetchTypeColumnAdapter
import tachiyomi.data.MangaUpdateStrategyColumnAdapter
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.category.anime.AnimeCategoryRepositoryImpl
import tachiyomi.data.category.manga.MangaCategoryRepositoryImpl
import tachiyomi.data.entries.anime.AnimeRepositoryImpl
import tachiyomi.data.entries.manga.MangaRepositoryImpl
import tachiyomi.data.handlers.anime.AndroidAnimeDatabaseHandler
import tachiyomi.data.handlers.manga.AndroidMangaDatabaseHandler
import tachiyomi.data.items.chapter.ChapterRepositoryImpl
import tachiyomi.data.items.episode.EpisodeRepositoryImpl
import tachiyomi.domain.category.anime.interactor.GetAnimeCategories
import tachiyomi.domain.category.manga.interactor.GetMangaCategories
import tachiyomi.domain.entries.anime.interactor.AnimeFetchInterval
import tachiyomi.domain.entries.anime.interactor.GetAnimeByUrlAndSourceId
import tachiyomi.domain.entries.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.entries.manga.interactor.MangaFetchInterval
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.track.anime.interactor.GetAnimeTracks
import tachiyomi.domain.track.manga.interactor.GetMangaTracks
import tachiyomi.mi.data.AnimeDatabase
import java.util.Date
import data.Chapter as SqlChapter
import data.Manga as SqlManga
import dataanime.Anime as SqlAnime
import dataanime.Anime_history as SqlAnimeHistory

class DuplicateBackupRestoreTest {

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `manga restore merges old duplicate chapters and history without losing local state`(
        existing: Boolean,
        legacy: Boolean,
    ) = runBlocking<Unit> {
        MangaFixture().use { fixture ->
            val firstMemo = memo("first" to "saved", "conflict" to "first")
            val secondMemo = memo("second" to "saved", "conflict" to "second")
            val backup = BackupManga(
                1,
                "/manga",
                title = "Manga",
                chapters = listOf(
                    BackupChapter(
                        "/duplicate",
                        "First",
                        scanlator = if (legacy) null else "",
                        bookmark = true,
                        lastPageRead = 2,
                        dateFetch = 30,
                        chapterNumber = 2.5F,
                        sourceOrder = 3,
                        memo = MemoColumnAdapter.encode(firstMemo),
                    ),
                    BackupChapter(
                        "/duplicate",
                        "Second",
                        scanlator = "Group",
                        read = true,
                        lastPageRead = 42,
                        dateFetch = 10,
                        dateUpload = 80,
                        memo = MemoColumnAdapter.encode(secondMemo),
                    ),
                    BackupChapter("/duplicate", "Third"),
                ),
                history = listOf(BackupHistory("/duplicate", 300, 20), BackupHistory("/duplicate", 100, 30)),
            )
            if (existing) {
                fixture.restorer.restore(
                    backup.copy(
                        chapters = listOf(
                            BackupChapter(
                                "/duplicate",
                                "Local",
                                scanlator = if (legacy) "LocalGroup" else null,
                                lastPageRead = 60,
                                memo = MemoColumnAdapter.encode(memo("local" to "saved", "conflict" to "local")),
                            ),
                        ),
                        history = listOf(BackupHistory("/duplicate", 400, 40)),
                    ),
                    emptyList(),
                )
            }
            val decoded = readBackup(Backup(backupManga = listOf(backup)), legacy).backupManga.single()
            decoded.chapters.size shouldBe 3

            repeat(2) {
                fixture.restorer.restore(decoded, emptyList())

                val manga = fixture.titles.getMangaByUrlAndSourceId("/manga", 1)!!
                val chapter = fixture.chapters.getChapterByMangaId(manga.id).single()
                chapter.read shouldBe true
                chapter.bookmark shouldBe true
                chapter.lastPageRead shouldBe if (existing) 60L else 42L
                chapter.name shouldBe if (existing) "Local" else "First"
                chapter.scanlator shouldBe if (existing && legacy) "LocalGroup" else "Group"
                chapter.memo shouldBe if (existing) {
                    memo("first" to "saved", "second" to "saved", "local" to "saved", "conflict" to "local")
                } else {
                    memo("first" to "saved", "second" to "saved", "conflict" to "first")
                }
                if (!existing) {
                    chapter.chapterNumber shouldBe 2.5
                    chapter.sourceOrder shouldBe 3L
                    chapter.dateFetch shouldBe 10L
                    chapter.dateUpload shouldBe 80L
                }
                val history = fixture.db.historyQueries.getHistoryByMangaId(manga.id).executeAsOne()
                history.last_read shouldBe Date(if (existing) 400 else 300)
                history.time_read shouldBe 50L
            }
        }
    }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `anime restore merges old duplicate episodes history and anime marks without losing local state`(
        existing: Boolean,
        legacy: Boolean,
    ) = runBlocking<Unit> {
        AnimeFixture().use { fixture ->
            val backup = BackupAnime(
                1,
                "/anime",
                title = "Anime",
                episodes = listOf(
                    BackupEpisode(
                        "/duplicate",
                        "First",
                        scanlator = if (legacy) null else "",
                        bookmark = true,
                        lastSecondSeen = 2,
                        dateFetch = 30,
                        episodeNumber = 2.5F,
                        sourceOrder = 3,
                        summary = if (legacy) null else "",
                        previewUrl = if (legacy) null else "",
                    ),
                    BackupEpisode(
                        "/duplicate", "Second", scanlator = "Group", seen = true, lastSecondSeen = 42,
                        totalSeconds = 120, fillermark = true, summary = "Summary", previewUrl = "/preview",
                        dateFetch = 10, dateUpload = 80,
                    ),
                    BackupEpisode("/duplicate", "Third"),
                ),
                history = listOf(BackupAnimeHistory("/duplicate", 300), BackupAnimeHistory("/duplicate", 100)),
            )
            if (existing) {
                fixture.restorer.restore(
                    backup.copy(
                        episodes = listOf(
                            BackupEpisode(
                                "/duplicate",
                                "Local",
                                scanlator = if (legacy) "LocalGroup" else null,
                                lastSecondSeen = 60,
                                totalSeconds = 150,
                                summary = if (legacy) "LocalSummary" else null,
                                previewUrl = if (legacy) "/local-preview" else null,
                            ),
                        ),
                        history = listOf(BackupAnimeHistory("/duplicate", 400)),
                    ),
                    emptyList(),
                    null,
                )
            }
            val decoded = readBackup(Backup(backupAnime = listOf(backup)), legacy).backupAnime.single()
            decoded.episodes.size shouldBe 3

            repeat(2) {
                val animeId = fixture.restorer.restore(decoded, emptyList(), null)

                val episode = fixture.episodes.getEpisodeByAnimeId(animeId).single()
                episode.seen shouldBe true
                episode.bookmark shouldBe true
                episode.fillermark shouldBe true
                episode.lastSecondSeen shouldBe if (existing) 60L else 42L
                episode.totalSeconds shouldBe if (existing) 150L else 120L
                episode.name shouldBe if (existing) "Local" else "First"
                episode.scanlator shouldBe if (existing && legacy) "LocalGroup" else "Group"
                episode.summary shouldBe if (existing && legacy) "LocalSummary" else "Summary"
                episode.previewUrl shouldBe if (existing && legacy) "/local-preview" else "/preview"
                if (!existing) {
                    episode.episodeNumber shouldBe 2.5
                    episode.sourceOrder shouldBe 3L
                    episode.dateFetch shouldBe 10L
                    episode.dateUpload shouldBe 80L
                }
                fixture.db.anime_historyQueries.getHistoryByAnimeId(animeId)
                    .executeAsOne().last_seen shouldBe Date(if (existing) 400 else 300)
            }
        }
    }

    private fun readBackup(backup: Backup, legacy: Boolean): Backup {
        val parser = ProtoBuf
        val bytes = if (legacy) {
            parser.encodeToByteArray(
                LegacyBackup.serializer(),
                LegacyBackup(backupManga = backup.backupManga, backupAnime = backup.backupAnime),
            )
        } else {
            parser.encodeToByteArray(Backup.serializer(), backup)
        }
        val mangas = mutableListOf<BackupManga>()
        val anime = mutableListOf<BackupAnime>()
        BackupReader(Buffer().write(bytes), parser, legacy).use { reader ->
            while (true) {
                when (reader.nextEntry() ?: break) {
                    BackupEntryType.MANGA -> mangas += reader.readMangaEntry()
                    BackupEntryType.ANIME -> anime += reader.readAnimeEntry()
                }
            }
        }
        return Backup(backupManga = mangas, backupAnime = anime)
    }

    private fun memo(vararg values: Pair<String, String>) =
        JsonObject(values.associate { (key, value) -> key to JsonPrimitive(value) })

    internal class MangaFixture : AutoCloseable {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
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
        private val handler = AndroidMangaDatabaseHandler(db, driver)
        val titles = MangaRepositoryImpl(handler)
        val creator = eu.kanade.tachiyomi.data.backup.create.creators.MangaBackupCreator(handler, titles)
        val categories = GetMangaCategories(MangaCategoryRepositoryImpl(handler))
        val chapters = ChapterRepositoryImpl(handler)
        private val getChapters = GetChaptersByMangaId(chapters)
        val restorer = MangaRestorer(
            handler = handler,
            getCategories = categories,
            getMangaByUrlAndSourceId = GetMangaByUrlAndSourceId(titles),
            getChaptersByMangaId = getChapters,
            updateManga = mockk<UpdateManga>(relaxed = true),
            getTracks = mockk<GetMangaTracks> { coEvery { await(any()) } returns emptyList() },
            insertTrack = mockk(),
            fetchInterval = MangaFetchInterval(getChapters),
        )

        init {
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            Database.Schema.create(driver)
        }

        override fun close() = driver.close()
    }

    internal class AnimeFixture : AutoCloseable {
        private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = AnimeDatabase(
            driver,
            anime_historyAdapter = SqlAnimeHistory.Adapter(DateColumnAdapter),
            animeAdapter = SqlAnime.Adapter(
                StringListColumnAdapter,
                AnimeUpdateStrategyColumnAdapter,
                FetchTypeColumnAdapter,
            ),
        )
        private val handler = AndroidAnimeDatabaseHandler(db, driver)
        val titles = AnimeRepositoryImpl(handler)
        val creator = eu.kanade.tachiyomi.data.backup.create.creators.AnimeBackupCreator(handler, titles)
        val categories = GetAnimeCategories(AnimeCategoryRepositoryImpl(handler))
        val episodes = EpisodeRepositoryImpl(handler)
        private val getEpisodes = GetEpisodesByAnimeId(episodes)
        val restorer = AnimeRestorer(
            handler = handler,
            getCategories = categories,
            getAnimeByUrlAndSourceId = GetAnimeByUrlAndSourceId(titles),
            getEpisodesByAnimeId = getEpisodes,
            updateAnime = mockk<UpdateAnime>(relaxed = true),
            getTracks = mockk<GetAnimeTracks> { coEvery { await(any()) } returns emptyList() },
            insertTrack = mockk(),
            fetchInterval = AnimeFetchInterval(getEpisodes),
        )

        init {
            driver.execute(null, "PRAGMA foreign_keys = ON", 0)
            AnimeDatabase.Schema.create(driver)
        }

        override fun close() = driver.close()
    }
}
