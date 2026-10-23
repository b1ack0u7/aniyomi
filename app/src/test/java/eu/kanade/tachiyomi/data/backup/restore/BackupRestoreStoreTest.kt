package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import io.kotest.matchers.shouldBe
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BackupRestoreStoreTest {
    @TempDir
    lateinit var directory: File

    private val parser = ProtoBuf

    @Test
    fun `interleaved entries can be read repeatedly in arbitrary order`() {
        val file = File(directory, "restore")
        val manga = BackupManga(
            source = 10,
            url = "/manga",
            title = "Manga",
            chapters = listOf(BackupChapter("/chapter", "Chapter", read = true, lastPageRead = 42)),
        )
        val anime = BackupAnime(
            source = 20,
            url = "/anime",
            title = "Anime",
            episodes = listOf(BackupEpisode("/episode", "Episode", seen = true, lastSecondSeen = 123)),
        )
        BackupRestoreStore(file, parser).use { store ->
            store.addManga(manga, isExisting = false)
            store.addAnime(anime, isExisting = true)
            store.addManga(manga.copy(url = "/second"), isExisting = false)
            store.addAnime(anime.copy(url = "/second"), isExisting = false)

            store.readAnime(store.animeEntries.last()).episodes shouldBe anime.episodes
            store.readManga(store.mangaEntries.last()).url shouldBe "/second"
            store.readManga(store.mangaEntries.first()).chapters.single().lastPageRead shouldBe 42L
            store.readAnime(store.animeEntries.first()).url shouldBe "/second"
            store.readManga(store.mangaEntries.first()).title shouldBe manga.title
            store.readAnime(store.animeEntries.last()).source shouldBe anime.source
        }
        file.exists() shouldBe false
    }

    @Test
    fun `both stacks restore new entries first then latest modified while preserving ties`() {
        BackupRestoreStore(File(directory, "restore"), parser).use { store ->
            listOf(
                Triple("existing-newest", 100L, true),
                Triple("new-oldest", 1L, false),
                Triple("new-first-tie", 10L, false),
                Triple("existing-oldest", 0L, true),
                Triple("new-second-tie", 10L, false),
            ).forEach { (url, modified, existing) ->
                store.addManga(BackupManga(10, url, lastModifiedAt = modified), existing)
                store.addAnime(BackupAnime(20, url, lastModifiedAt = modified), existing)
            }

            val expected = listOf(
                "new-first-tie",
                "new-second-tie",
                "new-oldest",
                "existing-newest",
                "existing-oldest",
            )
            store.mangaEntries.map { it.url } shouldBe expected
            store.animeEntries.map { it.url } shouldBe expected
            store.mangaEntries.map { store.readManga(it).url } shouldBe expected
            store.animeEntries.map { store.readAnime(it).url } shouldBe expected
        }
    }

    @Test
    fun `anime indexes retain parent relationships regardless of restore order`() {
        BackupRestoreStore(File(directory, "restore"), parser).use { store ->
            store.addAnime(BackupAnime(20, "/child", id = 200, parentId = 100), isExisting = false)
            store.addAnime(BackupAnime(20, "/parent", id = 100, lastModifiedAt = 10), isExisting = true)
            store.addAnime(BackupAnime(20, "/old-backup"), isExisting = false)

            val entries = store.animeEntries.associateBy { it.url }
            entries.getValue("/child").backupId shouldBe 200L
            entries.getValue("/child").parentId shouldBe 100L
            entries.getValue("/parent").backupId shouldBe 100L
            entries.getValue("/parent").parentId shouldBe null
            entries.getValue("/old-backup").backupId shouldBe null
            store.readAnime(entries.getValue("/child")).parentId shouldBe 100L
        }
    }

    @Test
    fun `large payloads are written to disk and indexes account for every byte`() {
        val file = File(directory, "restore")
        val description = "payload".repeat(100_000)
        BackupRestoreStore(file, parser).use { store ->
            repeat(4) { index ->
                store.addManga(BackupManga(10, "/manga/$index", description = description), isExisting = false)
                store.addAnime(BackupAnime(20, "/anime/$index", description = description), isExisting = false)
            }
            val entries = (store.mangaEntries + store.animeEntries).sortedBy { it.offset }
            file.length() shouldBe entries.sumOf { it.length.toLong() }
            (file.length() > description.length.toLong() * 8) shouldBe true
            entries.zipWithNext().forEach { (previous, next) ->
                next.offset shouldBe previous.offset + previous.length
            }
            store.readManga(store.mangaEntries.last()).description shouldBe description
            store.readAnime(store.animeEntries.first()).description shouldBe description
        }
    }

    @Test
    fun `closing repeatedly removes the spool file and clears indexes`() {
        val file = File(directory, "restore")
        val store = BackupRestoreStore(file, parser)
        store.addManga(BackupManga(10, "/manga"), isExisting = false)
        store.addAnime(BackupAnime(20, "/anime"), isExisting = false)

        store.close()
        store.close()

        file.exists() shouldBe false
        store.mangaEntries shouldBe emptyList()
        store.animeEntries shouldBe emptyList()
    }

    @Test
    fun `failure during restoration still cleans up the spool file`() {
        val file = File(directory, "restore")
        runCatching {
            BackupRestoreStore(file, parser).use { store ->
                store.addAnime(BackupAnime(20, "/anime"), isExisting = false)
                error("Restoration failed")
            }
        }.isFailure shouldBe true

        file.exists() shouldBe false
    }

    @Test
    fun `parallel anime and manga reads do not race the shared file position`() {
        BackupRestoreStore(File(directory, "restore"), parser).use { store ->
            val mangaDescription = "manga".repeat(10_000)
            val animeDescription = "anime".repeat(20_000)
            store.addManga(BackupManga(10, "/manga", description = mangaDescription), isExisting = false)
            store.addAnime(BackupAnime(20, "/anime", description = animeDescription), isExisting = false)
            val manga = store.mangaEntries.single()
            val anime = store.animeEntries.single()
            val executor = Executors.newFixedThreadPool(2)
            try {
                val mangaReader = executor.submit {
                    repeat(100) { store.readManga(manga).description shouldBe mangaDescription }
                }
                val animeReader = executor.submit {
                    repeat(100) { store.readAnime(anime).description shouldBe animeDescription }
                }
                mangaReader.get(10, TimeUnit.SECONDS)
                animeReader.get(10, TimeUnit.SECONDS)
            } finally {
                executor.shutdownNow()
            }
        }
    }
}
