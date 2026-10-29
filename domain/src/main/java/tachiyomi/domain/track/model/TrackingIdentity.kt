package tachiyomi.domain.track.model

data class TrackingIdentity(
    val trackId: Long,
    val parentId: Long,
    val trackerId: Long,
    val remoteId: Long,
    val libraryId: Long?,
    val remoteUrl: String,
) {
    fun acceptsLibraryResolution(updated: TrackingIdentity): Boolean {
        if (updated.copy(libraryId = libraryId) != this) return false
        if (updated.libraryId == libraryId) return true
        return (libraryId == null || libraryId == 0L) && updated.libraryId != null && updated.libraryId > 0
    }
}
