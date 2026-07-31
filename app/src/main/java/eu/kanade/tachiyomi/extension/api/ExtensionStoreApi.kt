package eu.kanade.tachiyomi.extension.api

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.protobuf.ProtoBuf
import okio.BufferedSource
import okio.buffer
import okio.gzip
import uy.kohesive.injekt.injectLazy

/**
 * Fetches the extension list of a repo using the `index_v2` format, which is announced by the
 * `index_v2` field of the repo's `repo.json`.
 *
 * The index may be served as protobuf or as JSON, optionally gzipped, so the encoding is
 * detected from the payload itself.
 */
class ExtensionStoreApi {

    private val networkService: NetworkHelper by injectLazy()

    /**
     * The app-wide [Json] can't be reused here: proto3 JSON encodes 64 bit integers as strings.
     */
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    suspend fun findExtensions(indexUrl: String): List<ExtensionStoreExtensionDto> {
        val store = fetch(indexUrl, ExtensionStoreDto.serializer())
        store.extensionList?.let { return it.extensions }

        val extensionListUrl = store.extensionListUrl ?: return emptyList()
        return fetch(extensionListUrl, ExtensionStoreListDto.serializer()).extensions
    }

    private suspend fun <T> fetch(url: String, deserializer: DeserializationStrategy<T>): T {
        val response = networkService.client
            .newCall(GET(url))
            .awaitSuccess()

        return response.body.source().decompressIfGzipped().use { source ->
            if (source.peek().readByte() == JSON_OBJECT_START) {
                json.decodeFromBufferedSource(deserializer, source)
            } else {
                ProtoBuf.decodeFromByteArray(deserializer, source.readByteArray())
            }
        }
    }

    private fun BufferedSource.decompressIfGzipped(): BufferedSource {
        val isGzipped = peek().use {
            try {
                it.readShort().toInt() and 0xFFFF == GZIP_MAGIC
            } catch (_: Exception) {
                false
            }
        }

        return if (isGzipped) gzip().buffer() else this
    }
}

private const val JSON_OBJECT_START = '{'.code.toByte()
private const val GZIP_MAGIC = 0x1F8B
