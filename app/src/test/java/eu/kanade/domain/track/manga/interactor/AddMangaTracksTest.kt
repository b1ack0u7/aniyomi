package eu.kanade.domain.track.manga.interactor

import eu.kanade.tachiyomi.data.database.models.manga.MangaTrack
import eu.kanade.tachiyomi.data.track.MangaTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tachiyomi.domain.history.manga.interactor.GetMangaHistory
import tachiyomi.domain.history.manga.model.MangaHistory
import tachiyomi.domain.items.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.items.chapter.model.Chapter
import java.time.Instant
import java.util.Date
import java.util.TimeZone

class AddMangaTracksTest {

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
            val tracker = mockk<MangaTracker>(relaxed = true)
            val getItems = mockk<GetChaptersByMangaId>()
            val getHistory = mockk<GetMangaHistory>()
            val item = MangaTrack.create(1).apply {
                title = "Entry"
                manga_id = 7
                last_chapter_read = 1.0
            }
            coEvery { getItems.await(7) } returns listOf(Chapter.create().copy(read = true, chapterNumber = 1.0))
            coEvery { getHistory.await(7) } returns listOf(
                MangaHistory.create().copy(readAt = Date(date + 60_000)),
                MangaHistory.create().copy(readAt = Date(date)),
            )
            val addTracks = AddMangaTracks(
                insertTrack = mockk(relaxed = true),
                syncChapterProgressWithTrack = mockk<SyncChapterProgressWithTrack>(relaxed = true),
                getChaptersByMangaId = getItems,
                trackerManager = mockk(),
                getHistory = getHistory,
            )

            addTracks.bind(tracker, item, 7)

            coVerify { tracker.setRemoteStartDate(match { it.started_reading_date == date }, date) }
        } finally {
            TimeZone.setDefault(originalZone)
        }
    }
}
