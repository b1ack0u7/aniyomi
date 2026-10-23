package eu.kanade.tachiyomi.data.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeSource
import eu.kanade.tachiyomi.data.backup.models.BackupAnimeTracking
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupCustomButtons
import eu.kanade.tachiyomi.data.backup.models.BackupEpisode
import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepos
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.FloatPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.IntPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.LegacyBackup
import eu.kanade.tachiyomi.data.backup.models.LongPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class BackupStreamTest {
    private val parser = ProtoBuf

    @Test
    fun `new streamed files are readable by the existing whole backup serializer`() {
        val expected = backup()
        val sink = Buffer()
        val writer = BackupWriter(sink, parser)
        expected.backupManga.forEach(writer::writeEntry)
        expected.backupAnime.forEach(writer::writeAnimeEntry)
        writer.writeMetadata(expected.copy(backupManga = emptyList(), backupAnime = emptyList()))

        val restored = parser.decodeFromByteArray(Backup.serializer(), sink.readByteArray())

        writer.isEmpty shouldBe false
        encode(restored).toList() shouldBe encode(expected).toList()
    }

    @Test
    fun `streaming reader accepts whole backup encoding with all six preference types and anime extras`() {
        val expected = backup()
        val bytes = encode(expected)
        val summary = BackupReader(Buffer().write(bytes), parser).use { it.readSummary() }

        summary.isLegacy shouldBe false
        summary.mangaCount shouldBe 1
        summary.animeCount shouldBe 2
        summary.metadata.backupManga shouldBe emptyList()
        summary.metadata.backupAnime shouldBe emptyList()
        encode(read(bytes, summary.isLegacy)).toList() shouldBe encode(expected).toList()
    }

    @Test
    fun `legacy anime fields and overlapping extension fields retain their original meaning`() {
        val modern = backup()
        val legacy = LegacyBackup(
            backupManga = modern.backupManga,
            backupCategories = modern.backupCategories,
            backupAnime = modern.backupAnime,
            backupAnimeCategories = modern.backupAnimeCategories,
            backupSources = modern.backupSources,
            backupAnimeSources = modern.backupAnimeSources,
            backupPreferences = modern.backupPreferences,
            backupSourcePreferences = modern.backupSourcePreferences,
            backupExtensions = modern.backupExtensions,
            backupAnimeExtensionRepo = modern.backupAnimeExtensionRepo,
            backupMangaExtensionRepo = modern.backupMangaExtensionRepo,
            backupCustomButton = modern.backupCustomButton,
        )
        val bytes = parser.encodeToByteArray(LegacyBackup.serializer(), legacy)
        val summary = BackupReader(Buffer().write(bytes), parser).use { it.readSummary() }

        summary.isLegacy shouldBe true
        summary.mangaCount shouldBe 1
        summary.animeCount shouldBe 2
        encode(read(bytes, summary.isLegacy)).toList() shouldBe encode(legacy.toBackup()).toList()
    }

    @Test
    fun `modern marker overrides old anime source detection and ignores legacy titles`() {
        val modern = backup()
        val oldFields = parser.encodeToByteArray(
            LegacyBackup.serializer(),
            LegacyBackup(backupAnime = modern.backupAnime, backupAnimeSources = modern.backupAnimeSources),
        )
        val bytes = oldFields + encode(modern)
        val summary = BackupReader(Buffer().write(bytes), parser).use { it.readSummary() }

        summary.isLegacy shouldBe false
        summary.animeCount shouldBe modern.backupAnime.size
        encode(read(bytes, false)).toList() shouldBe encode(modern).toList()
    }

    @Test
    fun `manga only upstream backup is detected as modern`() {
        val expected = Backup(backupManga = backup().backupManga)
        val bytes = encode(expected)
        val summary = BackupReader(Buffer().write(bytes), parser).use { it.readSummary() }

        summary.isLegacy shouldBe false
        summary.animeCount shouldBe 0
        encode(read(bytes, false)).toList() shouldBe encode(expected).toList()
    }

    @Test
    fun `metadata can precede titles and unknown fields of all supported wire types are skipped`() {
        val expected = backup()
        val input = Buffer().write(encode(expected.copy(backupManga = emptyList(), backupAnime = emptyList())))
        input.writeByte(0xA0).writeByte(0x01).writeByte(0x96).writeByte(0x01)
        input.writeByte(0xA9).writeByte(0x01).writeLongLe(42)
        input.writeByte(0xB2).writeByte(0x01).writeByte(4).writeUtf8("junk")
        input.writeByte(0xBD).writeByte(0x01).writeIntLe(42)
        val writer = BackupWriter(input, parser)
        expected.backupAnime.forEach(writer::writeAnimeEntry)
        expected.backupManga.forEach(writer::writeEntry)

        encode(read(input.readByteArray(), false)).toList() shouldBe encode(expected).toList()
    }

    @Test
    fun `summary skips payload decoding while validation pass rejects corrupt titles`() {
        val input = byteArrayOf(0x0A, 0x02, 0x0F, 0x00)
        val summary = BackupReader(Buffer().write(input), parser).use { it.readSummary() }

        summary.mangaCount shouldBe 1
        assertThrows<IOException> { read(input, false) }
    }

    @Test
    fun `title pass skips large known metadata without decoding or retaining it`() {
        val input = Buffer()
        input.writeByte(2 shl 3 or 2)
            .writeByte(0x80).writeByte(0x80).writeByte(0x40)
            .write(ByteArray(1024 * 1024) { 0x0F })
        val manga = backup().backupManga.single()
        BackupWriter(input, parser).writeEntry(manga)

        BackupReader(input, parser, collectMetadata = false).use { reader ->
            reader.nextEntry() shouldBe BackupEntryType.MANGA
            parser.encodeToByteArray(BackupManga.serializer(), reader.readMangaEntry()).toList() shouldBe
                parser.encodeToByteArray(BackupManga.serializer(), manga).toList()
            reader.nextEntry() shouldBe null
            assertThrows<IllegalStateException> { reader.readSummary() }
        }
    }

    @Test
    fun `empty backups stay empty`() {
        val sink = Buffer()
        val writer = BackupWriter(sink, parser)
        writer.writeMetadata(Backup())
        writer.isEmpty shouldBe true
        sink.size shouldBe 0L
        val summary = BackupReader(sink, parser).use { it.readSummary() }
        summary.mangaCount shouldBe 0
        summary.animeCount shouldBe 0
    }

    @Test
    fun `malformed framing and truncated metadata are rejected`() {
        listOf(
            byteArrayOf(0x0F),
            byteArrayOf(0x08, 0x01),
            byteArrayOf(0x02, 0x00),
            ByteArray(11) { 0xFF.toByte() },
            byteArrayOf(0x0A, 0x10, 0x01),
            byteArrayOf(0x12, 0x04, 0x0A),
            byteArrayOf(0x0A) + ByteArray(9) { 0x80.toByte() } + byteArrayOf(0x02),
            encode(backup()).dropLast(1).toByteArray(),
        ).forEach { bytes ->
            assertThrows<IOException> { BackupReader(Buffer().write(bytes), parser).use { it.readSummary() } }
        }
    }

    @Test
    fun `decoder opens raw and gzip old and modern backups and closes input`() {
        val modern = backup()
        val legacy = LegacyBackup(backupAnime = modern.backupAnime, backupAnimeSources = modern.backupAnimeSources)
        val inputs = listOf(encode(modern), parser.encodeToByteArray(LegacyBackup.serializer(), legacy))
        inputs.forEach { bytes ->
            listOf(bytes, gzip(bytes)).forEach { input ->
                val stream = TrackingInputStream(input)
                val uri = mockk<Uri>()
                val resolver = mockk<ContentResolver>()
                val context = mockk<Context>()
                every { context.contentResolver } returns resolver
                every { resolver.openInputStream(uri) } returns stream

                val summary = BackupDecoder(context, parser).decodeMetadata(uri)

                summary.animeCount shouldBe 2
                summary.isLegacy shouldBe (bytes !== inputs.first())
                stream.closed shouldBe true
            }
        }
    }

    @Test
    fun `decoder closes a corrupt gzip stream when opening fails`() {
        val stream = TrackingInputStream(byteArrayOf(0x1F, 0x8B.toByte(), 0x00))
        val uri = mockk<Uri>()
        val resolver = mockk<ContentResolver>()
        val context = mockk<Context>()
        every { context.contentResolver } returns resolver
        every { resolver.openInputStream(uri) } returns stream

        assertThrows<IOException> { BackupDecoder(context, parser).openReader(uri) }
        stream.closed shouldBe true
    }

    @Test
    fun `decoder rejects empty raw and gzip files and closes input`() {
        listOf(byteArrayOf(), gzip(byteArrayOf())).forEach { bytes ->
            val stream = TrackingInputStream(bytes)
            val uri = mockk<Uri>()
            val resolver = mockk<ContentResolver>()
            val context = mockk<Context>()
            every { context.contentResolver } returns resolver
            every { resolver.openInputStream(uri) } returns stream

            assertThrows<IOException> { BackupDecoder(context, parser).decodeMetadata(uri) }
            stream.closed shouldBe true
        }
    }

    private fun read(bytes: ByteArray, legacy: Boolean): Backup {
        return BackupReader(Buffer().write(bytes), parser, legacy).use { reader ->
            val manga = mutableListOf<BackupManga>()
            val anime = mutableListOf<BackupAnime>()
            while (true) {
                when (reader.nextEntry()) {
                    BackupEntryType.MANGA -> manga.add(reader.readMangaEntry())
                    BackupEntryType.ANIME -> anime.add(reader.readAnimeEntry())
                    null -> break
                }
            }
            reader.readMetadata().copy(backupManga = manga, backupAnime = anime)
        }
    }

    private fun encode(backup: Backup) = parser.encodeToByteArray(Backup.serializer(), backup)

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        GZIPOutputStream(output).use { it.write(bytes) }
        output.toByteArray()
    }

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }

    private fun backup() = Backup(
        isLegacy = false,
        backupManga = listOf(
            BackupManga(
                source = 1L,
                url = "/manga/1",
                chapters = listOf(BackupChapter(url = "/chapter/1", name = "One", read = true, lastPageRead = 4)),
                tracking = listOf(BackupTracking(syncId = 2, libraryId = 3L, mediaId = 7L)),
            ),
        ),
        backupAnime = listOf(
            BackupAnime(source = 2L, url = "/anime/1", id = 20),
            BackupAnime(
                source = 2L,
                url = "/anime/season/1",
                parentId = 20,
                id = 21,
                seasonNumber = 1.0,
                episodes = listOf(
                    BackupEpisode(
                        url = "/episode/1",
                        name = "One",
                        seen = true,
                        lastSecondSeen = 50,
                        fillermark = true,
                    ),
                ),
                tracking = listOf(BackupAnimeTracking(syncId = 3, libraryId = 4L, mediaId = 8L)),
            ),
        ),
        backupCategories = listOf(BackupCategory(name = "Reading", order = 0)),
        backupAnimeCategories = listOf(BackupCategory(name = "Watching", order = 1)),
        backupSources = listOf(BackupSource(name = "Manga", sourceId = 1)),
        backupAnimeSources = listOf(BackupAnimeSource(name = "Anime", sourceId = 2)),
        backupPreferences = listOf(
            BackupPreference("int", IntPreferenceValue(1)),
            BackupPreference("long", LongPreferenceValue(Long.MAX_VALUE)),
            BackupPreference("float", FloatPreferenceValue(1.5f)),
            BackupPreference("string", StringPreferenceValue("value")),
            BackupPreference("boolean", BooleanPreferenceValue(true)),
            BackupPreference("set", StringSetPreferenceValue(setOf("a", "b"))),
        ),
        backupSourcePreferences = listOf(
            BackupSourcePreferences("source_1", listOf(BackupPreference("lang", StringPreferenceValue("en")))),
        ),
        backupExtensions = listOf(BackupExtension("example.extension", byteArrayOf(1, 2, 3))),
        backupAnimeExtensionRepo = listOf(BackupExtensionRepos("https://anime.example", "Anime", null, "", "key")),
        backupMangaExtensionRepo = listOf(BackupExtensionRepos("https://manga.example", "Manga", "M", "", "key")),
        backupCustomButton = listOf(BackupCustomButtons("Skip", true, 0, "seek 90", "seek 180", "")),
    )
}
