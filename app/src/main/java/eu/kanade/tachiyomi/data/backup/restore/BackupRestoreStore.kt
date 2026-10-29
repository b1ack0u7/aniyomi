package eu.kanade.tachiyomi.data.backup.restore

import eu.kanade.tachiyomi.data.backup.models.BackupAnime
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

class BackupRestoreStore(
    private val file: File,
    private val parser: ProtoBuf,
) : Closeable {
    private val storage = RandomAccessFile(file, "rw").apply { setLength(0) }
    private val mangaIndex = mutableListOf<Index>()
    private val animeIndex = mutableListOf<Index>()
    private val mangaCategories = mutableMapOf<Identity, Categories>()
    private val animeCategories = mutableMapOf<Identity, Categories>()
    private var closed = false

    val mangaEntries: List<Index>
        @Synchronized
        get() = mangaIndex.sortedWith(ENTRY_ORDER)

    val animeEntries: List<Index>
        @Synchronized
        get() = animeIndex.sortedWith(ENTRY_ORDER)

    @Synchronized
    fun addManga(entry: BackupManga, isExisting: Boolean) {
        val payload = parser.encodeToByteArray(BackupManga.serializer(), entry)
        mangaIndex += append(payload, entry.source, entry.url, entry.title, entry.lastModifiedAt, isExisting)
        mangaCategories.merge(entry.source, entry.url, entry.favorite, entry.categories)
    }

    @Synchronized
    fun addAnime(entry: BackupAnime, isExisting: Boolean) {
        val payload = parser.encodeToByteArray(BackupAnime.serializer(), entry)
        animeIndex += append(payload, entry.source, entry.url, entry.title, entry.lastModifiedAt, isExisting)
            .copy(backupId = entry.id, parentId = entry.parentId)
        animeCategories.merge(entry.source, entry.url, entry.favorite, entry.categories)
    }

    @Synchronized
    fun readManga(index: Index): BackupManga {
        return parser.decodeFromByteArray(BackupManga.serializer(), read(index)).copy(
            categories = mangaCategories.getValue(Identity(index.source, index.url)).values.toList(),
        )
    }

    @Synchronized
    fun readAnime(index: Index): BackupAnime {
        return parser.decodeFromByteArray(BackupAnime.serializer(), read(index)).copy(
            categories = animeCategories.getValue(Identity(index.source, index.url)).values.toList(),
        )
    }

    private fun MutableMap<Identity, Categories>.merge(
        source: Long,
        url: String,
        favorite: Boolean,
        values: List<Long>,
    ) {
        val previous = getOrPut(Identity(source, url)) { Categories(favorite) }
        if (previous.favorite && !favorite) return
        if (favorite && !previous.favorite) {
            previous.values.clear()
            previous.favorite = true
        }
        previous.values.addAll(values)
    }

    private fun append(
        payload: ByteArray,
        source: Long,
        url: String,
        title: String,
        lastModifiedAt: Long,
        isExisting: Boolean,
    ): Index {
        check(!closed)
        val offset = storage.length()
        storage.seek(offset)
        storage.write(payload)
        return Index(offset, payload.size, source, url, title, lastModifiedAt, isExisting)
    }

    private fun read(index: Index): ByteArray {
        check(!closed)
        storage.seek(index.offset)
        return ByteArray(index.length).also(storage::readFully)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        try {
            storage.close()
        } finally {
            mangaIndex.clear()
            animeIndex.clear()
            mangaCategories.clear()
            animeCategories.clear()
            file.delete()
        }
    }

    private data class Identity(val source: Long, val url: String)

    private class Categories(var favorite: Boolean, val values: MutableSet<Long> = linkedSetOf())

    data class Index(
        val offset: Long,
        val length: Int,
        val source: Long,
        val url: String,
        val title: String,
        val lastModifiedAt: Long,
        val isExisting: Boolean,
        val backupId: Long? = null,
        val parentId: Long? = null,
    )

    companion object {
        private val ENTRY_ORDER = compareBy<Index> { it.isExisting }.thenByDescending { it.lastModifiedAt }
    }
}
