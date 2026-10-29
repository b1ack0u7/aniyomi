package eu.kanade.tachiyomi.data.backup

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Try to guess if the backup is an old aniyomi backup.
 *
 * The modern marker takes precedence over legacy-only fields and the old extension APK shape.
 */
object BackupDetector {
    @Serializable
    data class BackupDetector(
        @ProtoNumber(4) val backupAnimeCategories: List<LegacyMetadata> = emptyList(),
        @ProtoNumber(103) val backupAnimeSources: List<DetectAnimeSource> = emptyList(),
        @ProtoNumber(106) val extensionEntries: List<ExtensionEntry> = emptyList(),
        @ProtoNumber(107) val backupAnimeExtensionRepo: List<LegacyMetadata> = emptyList(),
        @ProtoNumber(108) val backupMangaExtensionRepo: List<LegacyMetadata> = emptyList(),
        @ProtoNumber(109) val backupCustomButton: List<LegacyMetadata> = emptyList(),
        @ProtoNumber(500) val isLegacy: Boolean = true,
    ) {
        @Serializable
        data class LegacyMetadata(@ProtoNumber(1) val name: String = "")

        @Serializable
        data class ExtensionEntry(
            @ProtoNumber(1) val name: String? = null,
            @ProtoNumber(4) val website: String? = null,
            @ProtoNumber(5) val signingKeyFingerprint: String? = null,
        )

        @Serializable
        data class DetectAnimeSource(
            @ProtoNumber(1) val name: String = "",
            @ProtoNumber(2) val sourceId: Long,
        )
    }

    fun isLegacyBackup(bytes: ByteArray, hasLegacyAnime: Boolean = false): Boolean {
        return try {
            val detect = ProtoBuf.decodeFromByteArray(BackupDetector.serializer(), bytes)
            detect.isLegacy &&
                (
                    hasLegacyAnime ||
                        detect.backupAnimeSources.isNotEmpty() ||
                        detect.backupAnimeCategories.isNotEmpty() ||
                        detect.backupAnimeExtensionRepo.isNotEmpty() ||
                        detect.backupMangaExtensionRepo.isNotEmpty() ||
                        detect.backupCustomButton.isNotEmpty() ||
                        detect.extensionEntries.any {
                            it.name != null && it.website == null && it.signingKeyFingerprint == null
                        }
                    )
        } catch (_: SerializationException) {
            false
        }
    }
}
