package eu.kanade.tachiyomi.extension.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Models for the `index_v2` extension store format, served either as protobuf or as JSON.
 *
 * Every field has a default because both encodings omit values that match the protobuf
 * default (empty strings, zeroed numbers, empty lists).
 */
@Serializable
data class ExtensionStoreDto(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(4) val contact: ExtensionStoreContactDto? = null,
    @ProtoNumber(101) val extensionList: ExtensionStoreListDto? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
)

@Serializable
data class ExtensionStoreContactDto(
    @ProtoNumber(1) val website: String = "",
    @ProtoNumber(2) val discord: String? = null,
)

@Serializable
data class ExtensionStoreListDto(
    @ProtoNumber(1) val extensions: List<ExtensionStoreExtensionDto> = emptyList(),
)

@Serializable
data class ExtensionStoreExtensionDto(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val packageName: String = "",
    @ProtoNumber(3) val resources: ExtensionStoreResourcesDto = ExtensionStoreResourcesDto(),
    @ProtoNumber(4) val extensionLib: String = "",
    @ProtoNumber(5) val versionCode: Long = 0,
    @ProtoNumber(6) val versionName: String = "",
    @ProtoNumber(7) val contentWarning: ExtensionContentWarning = ExtensionContentWarning.UNSPECIFIED,
    @ProtoNumber(8) val sources: List<ExtensionStoreSourceDto> = emptyList(),
)

@Serializable
data class ExtensionStoreResourcesDto(
    @ProtoNumber(1) val apkUrl: String = "",
    @ProtoNumber(2) val iconUrl: String = "",
)

@Serializable
data class ExtensionStoreSourceDto(
    @ProtoNumber(1) val id: Long = 0,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val language: String = "",
    @ProtoNumber(4) val homeUrl: String = "",
)

@Serializable
enum class ExtensionContentWarning {
    @ProtoNumber(0)
    @JsonNames("CONTENT_WARNING_UNSPECIFIED")
    UNSPECIFIED,

    @ProtoNumber(1)
    @JsonNames("CONTENT_WARNING_SAFE")
    SAFE,

    @ProtoNumber(2)
    @JsonNames("CONTENT_WARNING_MIXED")
    MIXED,

    @ProtoNumber(3)
    @JsonNames("CONTENT_WARNING_NSFW")
    NSFW,
}
