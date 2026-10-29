package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.BackupEntryType
import eu.kanade.tachiyomi.data.backup.BackupReader
import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeHistory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.LegacyBackup
import eu.kanade.tachiyomi.data.backup.restore.restorers.DuplicateBackupRestoreTest.AnimeFixture
import eu.kanade.tachiyomi.data.backup.restore.restorers.DuplicateBackupRestoreTest.MangaFixture
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.Date

class HistoryOnlyBackupRestoreTest {

    private val options = BackupOptions(chapters = false, categories = false, tracking = false, history = true)
    private val chapter = BackupChapter(
        "/same-child", "Saved chapter", scanlator = "Group", chapterNumber = 2.5F, sourceOrder = 3,
        dateFetch = 40, dateUpload = 50, read = true, bookmark = true, lastPageRead = 42, version = 8,
    )
    private val episode = BackupEpisode(
        "/same-child", "Saved episode", scanlator = "Group", episodeNumber = 2.5F, sourceOrder = 3,
        dateFetch = 40, dateUpload = 50, seen = true, bookmark = true, fillermark = true,
        lastSecondSeen = 42, totalSeconds = 120, version = 8, summary = "Summary", previewUrl = "/preview",
    )
    private val manga = BackupManga(
        1,
        "/same-parent",
        title = "Manga",
        chapters = listOf(chapter),
        history = listOf(BackupHistory(chapter.url, 300, 50)),
    )
    private val anime = BackupAnime(
        1,
        "/same-parent",
        title = "Anime",
        episodes = listOf(episode),
        history = listOf(BackupAnimeHistory(episode.url, 300)),
    )

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `manga history-only creation and streamed restore recover parent-scoped children without state`(
        existing: Boolean,
        legacyEnvelope: Boolean,
    ) = runBlocking<Unit> {
        MangaFixture().use { source ->
            MangaFixture().use { destination ->
                val entries = listOf(manga, manga.copy(source = 2), manga.copy(url = "/other-parent"))
                entries.forEach { source.restorer.restore(it, emptyList()) }
                if (existing) {
                    entries.forEach {
                        destination.restorer.restore(
                            it.copy(chapters = listOf(chapter.copy(name = "Local", lastPageRead = 60))),
                            emptyList(),
                        )
                    }
                }
                val created = source.creator(options).toList()
                created.flatMap { it.chapters } shouldBe emptyList()
                val backup = readBackup(Backup(backupManga = created), legacyEnvelope)
                repeat(2) {
                    val warnings = mutableListOf<String>()
                    backup.backupManga.forEach { destination.restorer.restore(it, emptyList(), warnings::add) }
                    warnings shouldBe emptyList()
                    entries.forEach { entry ->
                        val title = destination.titles.getMangaByUrlAndSourceId(entry.url, entry.source)!!
                        val saved = destination.chapters.getChapterByMangaId(title.id).single()
                        saved.name shouldBe if (existing) "Local" else chapter.name
                        saved.read shouldBe existing
                        saved.bookmark shouldBe existing
                        saved.lastPageRead shouldBe if (existing) 60L else 0L
                        saved.version shouldBe if (existing) 8L else 0L
                        saved.chapterNumber shouldBe 2.5
                        saved.scanlator shouldBe "Group"
                        val history = destination.db.historyQueries.getHistoryByMangaId(title.id).executeAsOne()
                        history.chapter_id shouldBe saved.id
                        history.last_read shouldBe Date(300)
                        history.time_read shouldBe 50L
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `anime history-only creation and streamed restore recover parent-scoped children without state`(
        existing: Boolean,
        legacyEnvelope: Boolean,
    ) = runBlocking<Unit> {
        AnimeFixture().use { source ->
            AnimeFixture().use { destination ->
                val entries = listOf(anime, anime.copy(source = 2), anime.copy(url = "/other-parent"))
                entries.forEach { source.restorer.restore(it, emptyList(), null) }
                if (existing) {
                    entries.forEach {
                        destination.restorer.restore(
                            it.copy(episodes = listOf(episode.copy(name = "Local", lastSecondSeen = 60))),
                            emptyList(),
                            null,
                        )
                    }
                }
                val created = source.creator(options).toList()
                created.flatMap { it.episodes } shouldBe emptyList()
                val backup = readBackup(Backup(backupAnime = created), legacyEnvelope)
                repeat(2) {
                    val warnings = mutableListOf<String>()
                    backup.backupAnime.forEach { destination.restorer.restore(it, emptyList(), null, warnings::add) }
                    warnings shouldBe emptyList()
                    entries.forEach { entry ->
                        val title = destination.titles.getAnimeByUrlAndSourceId(entry.url, entry.source)!!
                        val saved = destination.episodes.getEpisodeByAnimeId(title.id).single()
                        saved.name shouldBe if (existing) "Local" else episode.name
                        saved.seen shouldBe existing
                        saved.bookmark shouldBe existing
                        saved.fillermark shouldBe existing
                        saved.lastSecondSeen shouldBe if (existing) 60L else 0L
                        saved.totalSeconds shouldBe if (existing) 120L else 0L
                        saved.version shouldBe if (existing) 8L else 0L
                        saved.episodeNumber shouldBe 2.5
                        saved.summary shouldBe "Summary"
                        saved.previewUrl shouldBe "/preview"
                        val history = destination.db.anime_historyQueries.getHistoryByAnimeId(title.id).executeAsOne()
                        history.episode_id shouldBe saved.id
                        history.last_seen shouldBe Date(300)
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `non-library manga history survives reexport without manufacturing reading state`(
        legacyEnvelope: Boolean,
        reexportHistory: Boolean,
    ) = runBlocking<Unit> {
        MangaFixture().use { source ->
            MangaFixture().use { destination ->
                MangaFixture().use { finalDestination ->
                    val entries = listOf(
                        manga.copy(favorite = false),
                        manga.copy(source = 2, favorite = false),
                        manga.copy(url = "/other-parent", favorite = false),
                    )
                    entries.forEach { source.restorer.restore(it, emptyList()) }
                    source.creator(options.copy(readEntries = false)).toList() shouldBe emptyList()
                    val exported = readBackup(Backup(backupManga = source.creator(options).toList()), legacyEnvelope)
                    exported.backupManga.size shouldBe 3
                    repeat(2) {
                        exported.backupManga.forEach { destination.restorer.restore(it, emptyList()) }
                        destination.creator(options.copy(readEntries = false)).toList() shouldBe emptyList()
                        val reexported = destination.creator(options.copy(history = reexportHistory)).toList()
                        reexported.size shouldBe 3
                        reexported.flatMap { it.chapters } shouldBe emptyList()
                        reexported.forEach { it.history.size shouldBe if (reexportHistory) 1 else 0 }
                        val decoded = readBackup(Backup(backupManga = reexported), legacyEnvelope)
                        repeat(2) {
                            decoded.backupManga.forEach { finalDestination.restorer.restore(it, emptyList()) }
                        }
                        entries.forEach { entry ->
                            val restored = destination.titles.getMangaByUrlAndSourceId(entry.url, entry.source)!!
                            restored.favorite shouldBe false
                            val child = destination.chapters.getChapterByMangaId(restored.id).single()
                            child.read shouldBe false
                            child.bookmark shouldBe false
                            child.lastPageRead shouldBe 0L
                            val finalTitle = finalDestination.titles.getMangaByUrlAndSourceId(entry.url, entry.source)!!
                            finalTitle.favorite shouldBe false
                            val children = finalDestination.chapters.getChapterByMangaId(finalTitle.id)
                            children.size shouldBe if (reexportHistory) 1 else 0
                            if (reexportHistory) {
                                children.single().read shouldBe false
                                children.single().bookmark shouldBe false
                                children.single().lastPageRead shouldBe 0L
                                val history = finalDestination.db.historyQueries.getHistoryByMangaId(finalTitle.id)
                                    .executeAsOne()
                                history.chapter_id shouldBe children.single().id
                                history.last_read shouldBe Date(300)
                                history.time_read shouldBe 50L
                            } else {
                                finalDestination.db.historyQueries.getHistoryByMangaId(finalTitle.id)
                                    .executeAsList() shouldBe emptyList()
                            }
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `non-library anime history survives reexport without manufacturing watching state`(
        legacyEnvelope: Boolean,
        reexportHistory: Boolean,
    ) = runBlocking<Unit> {
        AnimeFixture().use { source ->
            AnimeFixture().use { destination ->
                AnimeFixture().use { finalDestination ->
                    val entries = listOf(
                        anime.copy(favorite = false),
                        anime.copy(source = 2, favorite = false),
                        anime.copy(url = "/other-parent", favorite = false),
                    )
                    entries.forEach { source.restorer.restore(it, emptyList(), null) }
                    source.creator(options.copy(readEntries = false)).toList() shouldBe emptyList()
                    val exported = readBackup(Backup(backupAnime = source.creator(options).toList()), legacyEnvelope)
                    exported.backupAnime.size shouldBe 3
                    repeat(2) {
                        exported.backupAnime.forEach { destination.restorer.restore(it, emptyList(), null) }
                        destination.creator(options.copy(readEntries = false)).toList() shouldBe emptyList()
                        val reexported = destination.creator(options.copy(history = reexportHistory)).toList()
                        reexported.size shouldBe 3
                        reexported.flatMap { it.episodes } shouldBe emptyList()
                        reexported.forEach { it.history.size shouldBe if (reexportHistory) 1 else 0 }
                        val decoded = readBackup(Backup(backupAnime = reexported), legacyEnvelope)
                        repeat(2) {
                            decoded.backupAnime.forEach { finalDestination.restorer.restore(it, emptyList(), null) }
                        }
                        entries.forEach { entry ->
                            val restored = destination.titles.getAnimeByUrlAndSourceId(entry.url, entry.source)!!
                            restored.favorite shouldBe false
                            val child = destination.episodes.getEpisodeByAnimeId(restored.id).single()
                            child.seen shouldBe false
                            child.bookmark shouldBe false
                            child.fillermark shouldBe false
                            child.lastSecondSeen shouldBe 0L
                            child.totalSeconds shouldBe 0L
                            val finalTitle = finalDestination.titles.getAnimeByUrlAndSourceId(entry.url, entry.source)!!
                            finalTitle.favorite shouldBe false
                            val children = finalDestination.episodes.getEpisodeByAnimeId(finalTitle.id)
                            children.size shouldBe if (reexportHistory) 1 else 0
                            if (reexportHistory) {
                                children.single().seen shouldBe false
                                children.single().bookmark shouldBe false
                                children.single().fillermark shouldBe false
                                children.single().lastSecondSeen shouldBe 0L
                                children.single().totalSeconds shouldBe 0L
                                val history = finalDestination.db.anime_historyQueries
                                    .getHistoryByAnimeId(finalTitle.id).executeAsOne()
                                history.episode_id shouldBe children.single().id
                                history.last_seen shouldBe Date(300)
                            } else {
                                finalDestination.db.anime_historyQueries.getHistoryByAnimeId(finalTitle.id)
                                    .executeAsList() shouldBe emptyList()
                            }
                        }
                    }
                }
            }
        }
    }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `manga chapter and history selections remain independent`(
        chapters: Boolean,
        history: Boolean,
    ) = runBlocking<Unit> {
        MangaFixture().use { fixture ->
            fixture.restorer.restore(manga, emptyList())
            val backup = fixture.creator(options.copy(chapters = chapters, history = history)).toList().single()
            backup.chapters.size shouldBe if (chapters) 1 else 0
            backup.history.size shouldBe if (history) 1 else 0
            if (chapters) backup.chapters.single().read shouldBe true
            if (history) {
                val identity = backup.history.single().chapter!!
                identity.read shouldBe false
                identity.bookmark shouldBe false
                identity.lastPageRead shouldBe 0L
                identity.version shouldBe 0L
                identity.toChapterImpl().memo.isEmpty() shouldBe true
            }
            MangaFixture().use { destination ->
                destination.restorer.restore(backup, emptyList())
                val title = destination.titles.getMangaByUrlAndSourceId(manga.url, manga.source)!!
                destination.chapters.getChapterByMangaId(title.id).size shouldBe if (chapters || history) 1 else 0
                destination.db.historyQueries.getHistoryByMangaId(title.id).executeAsList().size shouldBe
                    if (history) 1 else 0
            }
        }
    }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `anime episode and history selections remain independent`(
        episodes: Boolean,
        history: Boolean,
    ) = runBlocking<Unit> {
        AnimeFixture().use { fixture ->
            fixture.restorer.restore(anime, emptyList(), null)
            val backup = fixture.creator(options.copy(chapters = episodes, history = history)).toList().single()
            backup.episodes.size shouldBe if (episodes) 1 else 0
            backup.history.size shouldBe if (history) 1 else 0
            if (episodes) backup.episodes.single().seen shouldBe true
            if (history) {
                val identity = backup.history.single().episode!!
                identity.seen shouldBe false
                identity.bookmark shouldBe false
                identity.fillermark shouldBe false
                identity.lastSecondSeen shouldBe 0L
                identity.totalSeconds shouldBe 0L
                identity.version shouldBe 0L
            }
            AnimeFixture().use { destination ->
                val id = destination.restorer.restore(backup, emptyList(), null)
                destination.episodes.getEpisodeByAnimeId(id).size shouldBe if (episodes || history) 1 else 0
                destination.db.anime_historyQueries.getHistoryByAnimeId(id).executeAsList().size shouldBe
                    if (history) 1 else 0
            }
        }
    }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `legacy manga missing metadata warns rather than borrowing another parents child`(
        existing: Boolean,
        legacyEnvelope: Boolean,
    ) = runBlocking<Unit> {
        MangaFixture().use { fixture ->
            fixture.restorer.restore(manga.copy(source = 2), emptyList())
            fixture.restorer.restore(manga.copy(url = "/other-parent"), emptyList())
            if (existing) fixture.restorer.restore(manga.copy(history = emptyList()), emptyList())
            val backup = readBackup(Backup(backupManga = listOf(manga.copy(chapters = emptyList()))), legacyEnvelope)
                .backupManga.single()
            repeat(2) {
                val warnings = mutableListOf<String>()
                fixture.restorer.restore(backup, emptyList(), warnings::add)
                warnings.size shouldBe if (existing) 0 else 1
                val title = fixture.titles.getMangaByUrlAndSourceId(manga.url, manga.source)!!
                fixture.chapters.getChapterByMangaId(title.id).size shouldBe if (existing) 1 else 0
                fixture.db.historyQueries.getHistoryByMangaId(title.id).executeAsList().size shouldBe
                    if (existing) 1 else 0
                val other = fixture.titles.getMangaByUrlAndSourceId(manga.url, 2)!!
                fixture.db.historyQueries.getHistoryByMangaId(other.id).executeAsOne().time_read shouldBe 50L
            }
        }
    }

    @ParameterizedTest
    @CsvSource("false, false", "false, true", "true, false", "true, true")
    fun `legacy anime missing metadata warns rather than borrowing another parents child`(
        existing: Boolean,
        legacyEnvelope: Boolean,
    ) = runBlocking<Unit> {
        AnimeFixture().use { fixture ->
            fixture.restorer.restore(anime.copy(source = 2), emptyList(), null)
            fixture.restorer.restore(anime.copy(url = "/other-parent"), emptyList(), null)
            if (existing) fixture.restorer.restore(anime.copy(history = emptyList()), emptyList(), null)
            val backup = readBackup(Backup(backupAnime = listOf(anime.copy(episodes = emptyList()))), legacyEnvelope)
                .backupAnime.single()
            repeat(2) {
                val warnings = mutableListOf<String>()
                val id = fixture.restorer.restore(backup, emptyList(), null, warnings::add)
                warnings.size shouldBe if (existing) 0 else 1
                fixture.episodes.getEpisodeByAnimeId(id).size shouldBe if (existing) 1 else 0
                fixture.db.anime_historyQueries.getHistoryByAnimeId(id).executeAsList().size shouldBe
                    if (existing) 1 else 0
                val other = fixture.titles.getAnimeByUrlAndSourceId(anime.url, 2)!!
                fixture.db.anime_historyQueries.getHistoryByAnimeId(other.id).executeAsOne().last_seen shouldBe
                    Date(300)
            }
        }
    }

    @Test
    fun `manga history metadata is sanitized on restore and duplicate fallback is retained`() = runBlocking<Unit> {
        MangaFixture().use { fixture ->
            val backup = manga.copy(
                chapters = emptyList(),
                history = listOf(
                    BackupHistory(chapter.url, 100, 20, chapter.copy(url = "/wrong")),
                    BackupHistory(chapter.url, 300, 30, chapter),
                ),
            )
            repeat(2) {
                val warnings = mutableListOf<String>()
                fixture.restorer.restore(backup, emptyList(), warnings::add)
                warnings shouldBe emptyList()
                val title = fixture.titles.getMangaByUrlAndSourceId(manga.url, manga.source)!!
                val saved = fixture.chapters.getChapterByMangaId(title.id).single()
                saved.read shouldBe false
                saved.bookmark shouldBe false
                saved.lastPageRead shouldBe 0L
                fixture.db.historyQueries.getHistoryByMangaId(title.id).executeAsOne().time_read shouldBe 50L
            }
        }
    }

    @Test
    fun `anime history metadata is sanitized on restore and duplicate fallback is retained`() = runBlocking<Unit> {
        AnimeFixture().use { fixture ->
            val backup = anime.copy(
                episodes = emptyList(),
                history = listOf(
                    BackupAnimeHistory(episode.url, 100, episode = episode.copy(url = "/wrong")),
                    BackupAnimeHistory(episode.url, 300, episode = episode),
                ),
            )
            repeat(2) {
                val warnings = mutableListOf<String>()
                val id = fixture.restorer.restore(backup, emptyList(), null, warnings::add)
                warnings shouldBe emptyList()
                val saved = fixture.episodes.getEpisodeByAnimeId(id).single()
                saved.seen shouldBe false
                saved.bookmark shouldBe false
                saved.fillermark shouldBe false
                saved.lastSecondSeen shouldBe 0L
                saved.totalSeconds shouldBe 0L
                fixture.db.anime_historyQueries.getHistoryByAnimeId(id).executeAsOne().last_seen shouldBe Date(300)
            }
        }
    }

    @Test
    fun `manga mismatched metadata reports partial loss without inventing children`() = runBlocking<Unit> {
        MangaFixture().use { fixture ->
            val warnings = mutableListOf<String>()
            fixture.restorer.restore(
                manga.copy(
                    chapters = emptyList(),
                    history = listOf(
                        BackupHistory(chapter.url, 300, 50, chapter),
                        BackupHistory("/missing", 400, 60, chapter),
                    ),
                ),
                emptyList(),
                warnings::add,
            )
            warnings.size shouldBe 1
            val title = fixture.titles.getMangaByUrlAndSourceId(manga.url, manga.source)!!
            fixture.chapters.getChapterByMangaId(title.id).single().url shouldBe chapter.url
            fixture.db.historyQueries.getHistoryByMangaId(title.id).executeAsOne().time_read shouldBe 50L
        }
    }

    @Test
    fun `anime mismatched metadata reports partial loss without inventing children`() = runBlocking<Unit> {
        AnimeFixture().use { fixture ->
            val warnings = mutableListOf<String>()
            val id = fixture.restorer.restore(
                anime.copy(
                    episodes = emptyList(),
                    history = listOf(
                        BackupAnimeHistory(episode.url, 300, episode = episode),
                        BackupAnimeHistory("/missing", 400, episode = episode),
                    ),
                ),
                emptyList(),
                null,
                warnings::add,
            )
            warnings.size shouldBe 1
            fixture.episodes.getEpisodeByAnimeId(id).single().url shouldBe episode.url
            fixture.db.anime_historyQueries.getHistoryByAnimeId(id).executeAsOne().last_seen shouldBe Date(300)
        }
    }

    @Test
    fun `old history codecs skip optional metadata and new codecs accept its absence`() {
        val old = OldHistory("/child", 123, 50)
        val bytes = ProtoBuf.encodeToByteArray(OldHistory.serializer(), old)
        ProtoBuf.decodeFromByteArray(BackupHistory.serializer(), bytes).chapter shouldBe null
        ProtoBuf.decodeFromByteArray(BackupAnimeHistory.serializer(), bytes).episode shouldBe null
        ProtoBuf.decodeFromByteArray(
            OldHistory.serializer(),
            ProtoBuf.encodeToByteArray(BackupHistory.serializer(), BackupHistory(old.url, 123, 50, chapter)),
        ) shouldBe old
        ProtoBuf.decodeFromByteArray(
            OldHistory.serializer(),
            ProtoBuf.encodeToByteArray(
                BackupAnimeHistory.serializer(),
                BackupAnimeHistory(old.url, 123, 50, episode),
            ),
        ) shouldBe old
    }

    private fun readBackup(backup: Backup, legacy: Boolean): Backup {
        val bytes = if (legacy) {
            ProtoBuf.encodeToByteArray(
                LegacyBackup.serializer(),
                LegacyBackup(backupManga = backup.backupManga, backupAnime = backup.backupAnime),
            )
        } else {
            ProtoBuf.encodeToByteArray(Backup.serializer(), backup)
        }
        val mangas = mutableListOf<BackupManga>()
        val animes = mutableListOf<BackupAnime>()
        BackupReader(Buffer().write(bytes), ProtoBuf, legacy).use { reader ->
            while (true) {
                when (reader.nextEntry() ?: break) {
                    BackupEntryType.MANGA -> mangas += reader.readMangaEntry()
                    BackupEntryType.ANIME -> animes += reader.readAnimeEntry()
                }
            }
        }
        return Backup(backupManga = mangas, backupAnime = animes)
    }

    @Serializable
    private data class OldHistory(
        @ProtoNumber(1) val url: String,
        @ProtoNumber(2) val lastRead: Long,
        @ProtoNumber(3) val readDuration: Long = 0,
    )
}
