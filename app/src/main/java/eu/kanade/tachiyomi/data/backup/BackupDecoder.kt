package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.net.Uri
import kotlinx.serialization.protobuf.ProtoBuf
import okio.buffer
import okio.gzip
import okio.source
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

class BackupDecoder(
    private val context: Context,
    private val parser: ProtoBuf = Injekt.get(),
) {
    fun decodeMetadata(uri: Uri): BackupSummary = openReader(uri).use { it.readSummary() }

    /** Opens raw or gzipped protobuf; the caller owns the returned reader. */
    fun openReader(uri: Uri, legacy: Boolean = false, collectMetadata: Boolean = true): BackupReader {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open backup")
        var source = input.source().buffer()
        try {
            if (source.request(2) && source.peek().readShort().toInt() == GZIP_SIGNATURE) {
                source = source.gzip().buffer()
            }
            source.require(1)
            if (source.request(2) && source.peek().readShort().toInt() in JSON_SIGNATURES) {
                throw IOException(context.stringResource(MR.strings.invalid_backup_file_json))
            }
            return BackupReader(source, parser, legacy, collectMetadata)
        } catch (e: Exception) {
            source.close()
            throw e
        }
    }

    companion object {
        private const val GZIP_SIGNATURE = 0x1f8b
        private val JSON_SIGNATURES = setOf(0x7b7d, 0x7b22, 0x7b0a)
    }
}
