package eu.kanade.domain.track.anime.interactor

import eu.kanade.tachiyomi.data.database.models.anime.AnimeTrack
import eu.kanade.tachiyomi.data.track.AnimeTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tachiyomi.domain.history.anime.interactor.GetAnimeHistory
import tachiyomi.domain.history.anime.model.AnimeHistory
import tachiyomi.domain.items.episode.interactor.GetEpisodesByAnimeId
import tachiyomi.domain.items.episode.model.Episode
import java.time.Instant
import java.util.Date
import java.util.TimeZone

class AddAnimeTracksTest {

    @ParameterizedTest
    @CsvSource(
        "Pacific/Kiritimati, 2026-10-20T10:05:00Z",
        "America/Los_Angeles, 2026-10-21T06:55:00Z",
        "UTC, 2026-10-21T00:05:00Z",
    )
    fun `binding preserves the earliest history instant near local midnight`(
        zone: String,
        timestamp: String,
    ) = runTest {
        val originalZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val date = Instant.parse(timestamp).toEpochMilli()
            val tracker = mockk<AnimeTracker>(relaxed = true)
            val getItems = mockk<GetEpisodesByAnimeId>()
            val getHistory = mockk<GetAnimeHistory>()
            val item = AnimeTrack.create(1).apply {
                title = "Entry"
                anime_id = 7
                last_episode_seen = 1.0
            }
            coEvery { getItems.await(7) } returns listOf(Episode.create().copy(seen = true, episodeNumber = 1.0))
            coEvery { getHistory.await(7) } returns listOf(
                AnimeHistory.create().copy(seenAt = Date(date + 60_000)),
                AnimeHistory.create().copy(seenAt = Date(date)),
            )
            val addTracks = AddAnimeTracks(
                insertTrack = mockk(relaxed = true),
                syncChapterProgressWithTrack = mockk<SyncEpisodeProgressWithTrack>(relaxed = true),
                getEpisodesByAnimeId = getItems,
                trackerManager = mockk(),
                getHistory = getHistory,
            )

            addTracks.bind(tracker, item, 7)

            coVerify { tracker.setRemoteStartDate(match { it.started_watching_date == date }, date) }
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }
}
