package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.LegacyBackup
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import java.io.Closeable
import java.io.IOException

/** Writes the existing protobuf format while holding only one title's payload at a time. */
class BackupWriter(
    private val sink: BufferedSink,
    private val parser: ProtoBuf,
) {
    var isEmpty = true
        private set

    fun writeEntry(entry: BackupManga) {
        writeEntry(MANGA_FIELD, parser.encodeToByteArray(BackupManga.serializer(), entry))
    }

    fun writeAnimeEntry(entry: BackupAnime) {
        writeEntry(ANIME_FIELD, parser.encodeToByteArray(BackupAnime.serializer(), entry))
    }

    fun writeMetadata(metadata: Backup) {
        require(metadata.backupManga.isEmpty() && metadata.backupAnime.isEmpty())
        val bytes = parser.encodeToByteArray(Backup.serializer(), metadata)
        sink.write(bytes)
        if (bytes.isNotEmpty()) isEmpty = false
    }

    private fun writeEntry(field: Long, bytes: ByteArray) {
        sink.writeVarint(field shl 3 or WIRE_TYPE_LENGTH_DELIMITED.toLong())
        sink.writeVarint(bytes.size.toLong())
        sink.write(bytes)
        isEmpty = false
    }
}

enum class BackupEntryType { MANGA, ANIME }

data class BackupSummary(
    val metadata: Backup,
    val mangaCount: Int,
    val animeCount: Int,
    val isLegacy: Boolean,
)

/** Reads titles individually, collecting only recognized non-title fields for the metadata pass. */
class BackupReader(
    private val source: BufferedSource,
    private val parser: ProtoBuf,
    private val legacy: Boolean = false,
    private val collectMetadata: Boolean = true,
) : Closeable {
    private val metadata = Buffer()
    private var entryLength = NO_ENTRY
    private var entryType: BackupEntryType? = null
    private var mangaCount = 0
    private var animeCount = 0
    private var legacyAnimeCount = 0
    private var summary: BackupSummary? = null

    fun nextEntry(): BackupEntryType? {
        check(entryLength == NO_ENTRY)
        while (!source.exhausted()) {
            val tag = source.readVarint()
            val field = tag ushr 3
            val wireType = (tag and 0x7).toInt()
            if (field !in 1..MAX_FIELD_NUMBER) throw IOException("Invalid field number $field")
            if (field in TITLE_FIELDS && wireType != WIRE_TYPE_LENGTH_DELIMITED) {
                throw IOException("Invalid title wire type $wireType")
            }

            if (wireType == WIRE_TYPE_LENGTH_DELIMITED && field in TITLE_FIELDS) {
                val length = source.readLength()
                val type = when (field) {
                    MANGA_FIELD -> {
                        mangaCount++
                        BackupEntryType.MANGA
                    }
                    ANIME_FIELD -> {
                        animeCount++
                        BackupEntryType.ANIME.takeUnless { legacy }
                    }
                    else -> {
                        legacyAnimeCount++
                        BackupEntryType.ANIME.takeIf { legacy }
                    }
                }
                if (type == null) {
                    source.skip(length)
                    continue
                }
                entryLength = length
                entryType = type
                return type
            }

            val destination = metadata.takeIf { collectMetadata && field in METADATA_FIELDS }
            destination?.writeVarint(tag)
            when (wireType) {
                WIRE_TYPE_VARINT -> {
                    val value = source.readVarint()
                    destination?.writeVarint(value)
                }
                WIRE_TYPE_FIXED64 -> copyOrSkip(destination, 8)
                WIRE_TYPE_LENGTH_DELIMITED -> {
                    val length = source.readLength()
                    destination?.writeVarint(length)
                    copyOrSkip(destination, length)
                }
                WIRE_TYPE_FIXED32 -> copyOrSkip(destination, 4)
                else -> throw IOException("Unsupported wire type $wireType")
            }
        }
        return null
    }

    fun readMangaEntry(): BackupManga {
        check(entryType == BackupEntryType.MANGA)
        return decode(BackupManga.serializer(), source.readByteArray(takeEntryLength()))
    }

    fun readAnimeEntry(): BackupAnime {
        check(entryType == BackupEntryType.ANIME)
        return decode(BackupAnime.serializer(), source.readByteArray(takeEntryLength()))
    }

    fun skipEntry() {
        source.skip(takeEntryLength())
    }

    /** Counts entries without decoding them; detection matches older releases' legacy heuristic. */
    fun readSummary(): BackupSummary {
        check(collectMetadata)
        summary?.let { return it }
        if (entryLength != NO_ENTRY) skipEntry()
        while (nextEntry() != null) skipEntry()
        val bytes = metadata.readByteArray()
        val isLegacy = BackupDetector.isLegacyBackup(bytes)
        val backup = if (isLegacy) {
            decode(LegacyBackup.serializer(), bytes).toBackup()
        } else {
            decode(Backup.serializer(), bytes)
        }
        return BackupSummary(backup, mangaCount, if (isLegacy) legacyAnimeCount else animeCount, isLegacy)
            .also { summary = it }
    }

    fun readMetadata(): Backup = readSummary().metadata

    override fun close() {
        source.close()
    }

    private fun copyOrSkip(destination: Buffer?, length: Long) {
        if (destination == null) source.skip(length) else destination.write(source, length)
    }

    private fun takeEntryLength(): Long {
        check(entryLength != NO_ENTRY)
        entryType = null
        return entryLength.also { entryLength = NO_ENTRY }
    }

    private fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T {
        return try {
            parser.decodeFromByteArray(deserializer, bytes)
        } catch (e: SerializationException) {
            throw IOException(e)
        }
    }
}

private fun BufferedSource.readVarint(): Long {
    var result = 0L
    for (shift in 0 until Long.SIZE_BITS step 7) {
        val byte = readByte().toInt() and 0xFF
        if (shift == 63 && byte > 1) throw IOException("Malformed varint")
        result = result or ((byte and 0x7F).toLong() shl shift)
        if ((byte and 0x80) == 0) return result
    }
    throw IOException("Malformed varint")
}

private fun BufferedSource.readLength(): Long {
    val length = readVarint()
    if (length !in 0..Int.MAX_VALUE) throw IOException("Invalid length $length")
    return length
}

private fun BufferedSink.writeVarint(value: Long) {
    var remaining = value
    while ((remaining and 0x7FL.inv()) != 0L) {
        writeByte((remaining and 0x7F).toInt() or 0x80)
        remaining = remaining ushr 7
    }
    writeByte(remaining.toInt())
}

private const val WIRE_TYPE_VARINT = 0
private const val WIRE_TYPE_FIXED64 = 1
private const val WIRE_TYPE_LENGTH_DELIMITED = 2
private const val WIRE_TYPE_FIXED32 = 5
private const val MANGA_FIELD = 1L
private const val LEGACY_ANIME_FIELD = 3L
private const val ANIME_FIELD = 501L
private const val MAX_FIELD_NUMBER = 0x1FFFFFFFL
private const val NO_ENTRY = -1L
private val TITLE_FIELDS = setOf(MANGA_FIELD, LEGACY_ANIME_FIELD, ANIME_FIELD)
private val METADATA_FIELDS = setOf(
    2L, 4L, 101L, 103L, 104L, 105L, 106L, 107L, 108L, 109L, 500L, 502L, 503L, 504L, 505L, 506L,
)
