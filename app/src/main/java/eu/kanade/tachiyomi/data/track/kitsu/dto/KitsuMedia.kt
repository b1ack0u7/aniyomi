package eu.kanade.tachiyomi.data.track.kitsu.dto

import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.kitsu.KitsuApi
import eu.kanade.tachiyomi.data.track.model.AnimeTrackSearch
import eu.kanade.tachiyomi.data.track.model.MangaTrackSearch
import kotlinx.serialization.Serializable

@Serializable
internal data class KitsuMediaResult(val data: List<KitsuMedia>)

@Serializable
internal data class KitsuMedia(val id: Long, val attributes: KitsuMediaAttributes) {
    fun toMangaTrack(): MangaTrackSearch = MangaTrackSearch.create(TrackerManager.KITSU).apply {
        remote_id = this@KitsuMedia.id
        title = attributes.canonicalTitle
        total_chapters = attributes.chapterCount ?: 0
        cover_url = attributes.posterImage?.original.orEmpty()
        summary = attributes.synopsis.orEmpty()
        tracking_url = KitsuApi.mangaUrl(this@KitsuMedia.id)
        score = attributes.averageRating ?: -1.0
        publishing_status = attributes.status.orEmpty()
        publishing_type = attributes.subtype.orEmpty()
        start_date = attributes.startDate.orEmpty()
    }

    fun toAnimeTrack(): AnimeTrackSearch = AnimeTrackSearch.create(TrackerManager.KITSU).apply {
        remote_id = this@KitsuMedia.id
        title = attributes.canonicalTitle
        total_episodes = attributes.episodeCount ?: 0
        cover_url = attributes.posterImage?.original.orEmpty()
        summary = attributes.synopsis.orEmpty()
        tracking_url = KitsuApi.animeUrl(this@KitsuMedia.id)
        score = attributes.averageRating ?: -1.0
        publishing_status = attributes.status.orEmpty()
        publishing_type = attributes.subtype.orEmpty()
        start_date = attributes.startDate.orEmpty()
    }
}

@Serializable
internal data class KitsuMediaAttributes(
    val canonicalTitle: String,
    val chapterCount: Long? = null,
    val episodeCount: Long? = null,
    val posterImage: KitsuSearchItemCover? = null,
    val synopsis: String? = null,
    val averageRating: Double? = null,
    val status: String? = null,
    val subtype: String? = null,
    val startDate: String? = null,
)
