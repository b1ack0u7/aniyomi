package eu.kanade.tachiyomi.data.track.simkl.dto

import kotlinx.serialization.Serializable

@Serializable
data class SimklUser(
    val account: SimklUserAccount,
    val user: SimklUserProfile,
)

@Serializable
data class SimklUserProfile(
    val name: String,
)

@Serializable
data class SimklUserAccount(
    val id: Int,
)
