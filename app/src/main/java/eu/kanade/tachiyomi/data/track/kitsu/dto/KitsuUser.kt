package eu.kanade.tachiyomi.data.track.kitsu.dto

import kotlinx.serialization.Serializable

@Serializable
internal data class KitsuCurrentAccountResult(
    val data: KitsuCurrentAccountData,
)

@Serializable
internal data class KitsuCurrentAccountData(
    val currentAccount: KitsuAccount,
)

@Serializable
internal data class KitsuAccount(
    val id: String,
    val ratingSystem: String? = null,
    val profile: KitsuProfile,
)

@Serializable
internal data class KitsuProfile(
    val name: String,
)
