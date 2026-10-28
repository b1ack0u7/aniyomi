package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.manga.Chapter
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.SourceAccessRestriction
import eu.kanade.tachiyomi.network.SourceRequestCoordinator
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.collectSourceAccessWarnings
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.atomic.AtomicInteger

class HttpPageLoaderTest {
    @ParameterizedTest
    @ValueSource(ints = [403, 429])
    fun `image access errors retain their cause for both fresh and cached page lists`(code: Int) = runBlocking {
        for (cachedPages in listOf(false, true)) {
            val fixture = Fixture(cachedPages)
            coEvery { fixture.source.getImage(any()) } returns Response.Builder()
                .request(Request.Builder().url("https://example.test/image").build())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("Rejected")
                .header("Retry-After", "60")
                .body("rejected".toResponseBody())
                .build()
            try {
                val page = fixture.loader.getPages().single()
                fixture.loader.preloadPages(listOf(page), 0)
                withTimeout(5_000) { page.statusFlow.first { it == Page.State.ERROR } }

                page.errorFlow.value.shouldBeInstanceOf<HttpException>().code shouldBe code
                val restriction = if (code == 403) {
                    SourceAccessRestriction.Forbidden()
                } else {
                    SourceAccessRestriction.RateLimited(60_000)
                }
                val warning = CompletableDeferred<SourceAccessRestriction>()
                val observer = launch {
                    page.collectSourceAccessWarnings(flowOf(restriction), flowOf(true)) {
                        warning.complete(it)
                        true
                    }
                }
                try {
                    withTimeout(5_000) { warning.await() } shouldBe restriction
                } finally {
                    observer.cancelAndJoin()
                }
                coVerify(exactly = if (cachedPages) 0 else 1) { fixture.source.getPageList(any()) }
                coVerify(exactly = 1) { fixture.source.getImage(any()) }
            } finally {
                fixture.loader.recycle()
            }
        }
    }

    @Test
    fun `cached images remain readable without source requests or source access errors`() = runBlocking {
        val fixture = Fixture(cachedPages = true, cachedImage = true)
        try {
            val page = fixture.loader.getPages().single()
            fixture.loader.preloadPages(listOf(page), 0)
            withTimeout(5_000) { page.statusFlow.first { it == Page.State.READY } }

            page.errorFlow.value shouldBe null
            coVerify(exactly = 0) { fixture.requests.withReaderRequest<Any?>(any(), any()) }
            coVerify(exactly = 0) { fixture.source.getImage(any()) }
        } finally {
            fixture.loader.recycle()
        }
    }

    @Test
    fun `cache eviction cannot admit a third network transfer`() = runBlocking<Unit> {
        val sourceId = 8_765_432_101L
        val release = CompletableDeferred<Unit>()
        val admitted = List(2) { CompletableDeferred<Unit>() }
        val occupants = admitted.map { signal ->
            launch(Dispatchers.IO) {
                SourcePageLoadCoordinator.instance.withPermit(sourceId, foreground = true) {
                    signal.complete(Unit)
                    release.await()
                }
            }
        }
        withTimeout(5_000) { admitted.forEach { it.await() } }
        val classifiedCached = CompletableDeferred<Unit>()
        val imageRequested = CompletableDeferred<Unit>()
        val checks = AtomicInteger()
        val fixture = Fixture(
            cachedPages = true,
            sourceId = sourceId,
            cachePresence = {
                val present = checks.incrementAndGet() == 1
                if (present) classifiedCached.complete(Unit)
                present
            },
        )
        coEvery { fixture.source.getImage(any()) } coAnswers {
            imageRequested.complete(Unit)
            throw HttpException(403)
        }
        try {
            val page = fixture.loader.getPages().single()
            fixture.loader.preloadPages(listOf(page), 0)
            withTimeout(5_000) { classifiedCached.await() }
            withTimeoutOrNull(500) { imageRequested.await() } shouldBe null
            checks.get() shouldBe 2

            release.complete(Unit)
            withTimeout(5_000) { page.statusFlow.first { it == Page.State.ERROR } }
            coVerify(exactly = 1) { fixture.source.getImage(any()) }
        } finally {
            fixture.loader.recycle()
            release.complete(Unit)
            occupants.forEach { it.join() }
        }
    }

    private class Fixture(
        cachedPages: Boolean,
        cachedImage: Boolean = false,
        sourceId: Long = 1L,
        cachePresence: () -> Boolean = { cachedImage },
    ) {
        val source = mockk<HttpSource> { every { id } returns sourceId }
        val requests = mockk<SourceRequestCoordinator>()
        private val pages = listOf(Page(0, imageUrl = "https://example.test/image"))
        private val cache = mockk<ChapterCache> {
            every { isImageInCache(any()) } answers { cachePresence() }
            if (cachedPages) {
                every { getPageListFromCache(any()) } returns pages
            } else {
                every { getPageListFromCache(any()) } throws IllegalStateException("Cache miss")
            }
        }
        private val preferences = mockk<SourcePreferences> {
            every { dataSaver() } returns mockk { every { get() } returns SourcePreferences.DataSaver.NONE }
        }
        private val chapter = ReaderChapter(
            mockk<Chapter>(relaxed = true) {
                every { id } returns 1L
                every { manga_id } returns 1L
            },
        )
        val loader = HttpPageLoader(chapter, source, cache, preferences, sourceRequests = requests)

        init {
            coEvery { source.getPageList(any()) } returns pages
            coEvery { requests.withReaderRequest<Any?>(any(), any()) } coAnswers {
                secondArg<suspend () -> Any?>().invoke()
            }
        }
    }
}
