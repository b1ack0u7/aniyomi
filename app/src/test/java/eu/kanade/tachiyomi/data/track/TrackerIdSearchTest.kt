package eu.kanade.tachiyomi.data.track

import eu.kanade.tachiyomi.data.track.anilist.AnilistApi
import eu.kanade.tachiyomi.data.track.bangumi.BangumiApi
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesApi
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriApi
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

class TrackerIdSearchTest {

    private val originalInjekt = Injekt
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }
    private val requests = mutableListOf<Request>()
    private var responseBody = ""
    private var responseCode = 200
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        requests.add(chain.request())
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(responseCode)
            .message("test")
            .body(responseBody.toResponseBody("application/json".toMediaType()))
            .build()
    }.build()

    @BeforeEach
    fun setUp() {
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(json)
    }

    @AfterEach
    fun tearDown() {
        Injekt = originalInjekt
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test
    fun `AniList uses numeric ids and preserves the media type`(): Unit = runBlocking {
        val api = AnilistApi(client, mockk())
        responseBody = ANILIST_RESULT
        api.search("id: 42 ").single().remote_id shouldBe 42L
        api.searchAnime("id:42").single().remote_id shouldBe 42L
        requests.forEach { request ->
            request.method shouldBe "POST"
            val payload = request.payload()
            payload["variables"]!!.jsonObject["id"]!!.jsonPrimitive.content shouldBe "42"
            payload["variables"]!!.jsonObject.containsKey("query") shouldBe false
        }
        requests[0].payload()["query"]!!.jsonPrimitive.content.contains("type: MANGA") shouldBe true
        requests[1].payload()["query"]!!.jsonPrimitive.content.contains("type: ANIME") shouldBe true
    }

    @Test
    fun `AniList rejects invalid ids without requests`(): Unit = runBlocking {
        val api = AnilistApi(client, mockk())
        invalidIds.forEach {
            api.search(it) shouldBe emptyList()
            api.searchAnime(it) shouldBe emptyList()
        }
        requests shouldBe emptyList()
    }

    @Test
    fun `AniList keeps title search and accepts empty results`(): Unit = runBlocking {
        val api = AnilistApi(client, mockk())
        responseBody = """{"data":{"Page":{"media":[]}}}"""
        api.search("One Piece") shouldBe emptyList()
        api.searchAnime("One Piece") shouldBe emptyList()
        requests.forEach {
            it.payload()["variables"]!!.jsonObject["query"]!!.jsonPrimitive.content shouldBe "One Piece"
        }
        api.search("id:42") shouldBe emptyList()
        api.searchAnime("id:42") shouldBe emptyList()
    }

    @Test
    fun `Bangumi fetches subjects directly and keeps anime and manga separate`(): Unit = runBlocking {
        val api = BangumiApi(9, client, mockk())
        responseBody = bangumiSubject(1)
        api.search("id: 42 ").single().remote_id shouldBe 42L
        api.searchAnime("id:42") shouldBe emptyList()
        responseBody = bangumiSubject(2)
        api.searchAnime("id:42").single().remote_id shouldBe 42L
        api.search("id:42") shouldBe emptyList()
        requests.forEach {
            it.method shouldBe "GET"
            it.url.encodedPath shouldBe "/v0/subjects/42"
        }
    }

    @Test
    fun `Bangumi rejects invalid ids and missing subjects`(): Unit = runBlocking {
        val api = BangumiApi(9, client, mockk())
        invalidIds.forEach {
            api.search(it) shouldBe emptyList()
            api.searchAnime(it) shouldBe emptyList()
        }
        requests shouldBe emptyList()
        responseCode = 404
        api.search("id:42") shouldBe emptyList()
        api.searchAnime("id:42") shouldBe emptyList()
    }

    @Test
    fun `Bangumi retains normal search and manga platform filtering`(): Unit = runBlocking {
        val api = BangumiApi(9, client, mockk())
        responseBody = """{"total":1,"limit":20,"offset":0,"data":[${bangumiSubject(1)}]}"""
        api.search("Title").single().title shouldBe "Title"
        api.searchAnime("Title").single().title shouldBe "Title"
        requests.forEach {
            it.method shouldBe "POST"
            it.url.encodedPath shouldBe "/v0/search/subjects"
            it.payload()["keyword"]!!.jsonPrimitive.content shouldBe "Title"
        }
        responseBody = bangumiSubject(1).replace("漫画", "小说")
        api.search("id:42") shouldBe emptyList()
    }

    @Test
    fun `Shikimori uses ids on the matching media endpoint`(): Unit = runBlocking {
        val api = ShikimoriApi(7, client, mockk())
        responseBody = SHIKIMORI_RESULT
        api.search("id: 42 ").single().remote_id shouldBe 42L
        api.searchAnime("id:42").single().remote_id shouldBe 42L
        requests[0].url.encodedPath shouldBe "/api/mangas"
        requests[1].url.encodedPath shouldBe "/api/animes"
        requests.forEach {
            it.url.queryParameter("ids") shouldBe "42"
            it.url.queryParameter("search") shouldBe null
        }
    }

    @Test
    fun `Shikimori rejects invalid ids and accepts empty id results`(): Unit = runBlocking {
        val api = ShikimoriApi(7, client, mockk())
        invalidIds.forEach {
            api.search(it) shouldBe emptyList()
            api.searchAnime(it) shouldBe emptyList()
        }
        requests shouldBe emptyList()
        responseBody = "[]"
        api.search("id:42") shouldBe emptyList()
        api.searchAnime("id:42") shouldBe emptyList()
    }

    @Test
    fun `Shikimori preserves normal title search`(): Unit = runBlocking {
        val api = ShikimoriApi(7, client, mockk())
        responseBody = "[]"
        api.search("One Piece") shouldBe emptyList()
        api.searchAnime("One Piece") shouldBe emptyList()
        requests.forEach {
            it.url.queryParameter("search") shouldBe "One Piece"
            it.url.queryParameter("ids") shouldBe null
        }
    }

    @Test
    fun `MangaUpdates supports decimal and base36 ids`(): Unit = runBlocking {
        val api = MangaUpdatesApi(mockk(), client)
        responseBody = """{"series_id":42,"title":"Title"}"""
        api.search("id: 42 ").single().seriesId shouldBe 42L
        responseBody = """{"series_id":16,"title":"Title"}"""
        api.search("id:16").single().seriesId shouldBe 16L
        responseBody = """{"series_id":82509,"title":"Title"}"""
        api.search("id:1rnx").single().seriesId shouldBe 82509L
        requests.map { it.url.encodedPath } shouldBe listOf(
            "/v1/series/42",
            "/v1/series/16",
            "/v1/series/82509",
        )
        requests.forEach { it.method shouldBe "GET" }
    }

    @Test
    fun `MangaUpdates rejects invalid ids and handles a missing series`(): Unit = runBlocking {
        val api = MangaUpdatesApi(mockk(), client)
        listOf("id:", "id:!", "id:0", "id:-1", "id:9999999999999999999999999999").forEach {
            api.search(it) shouldBe emptyList()
        }
        requests shouldBe emptyList()
        responseCode = 404
        api.search("id:42") shouldBe emptyList()
    }

    @Test
    fun `MangaUpdates preserves title searches and surfaces server failures`(): Unit = runBlocking {
        val api = MangaUpdatesApi(mockk(), client)
        responseBody = """{"results":[{"record":{"series_id":42,"title":"Title"}}]}"""
        api.search("Title").single().seriesId shouldBe 42L
        requests.single().payload()["search"]!!.jsonPrimitive.content shouldBe "Title"
        responseCode = 500
        assertThrows<HttpException> { api.search("id:42") }
    }

    @Test
    fun `AniList profile includes display name and score format`(): Unit = runBlocking {
        val api = AnilistApi(client, mockk())
        responseBody = """{"data":{"Viewer":{"id":12,"name":"Name","mediaListOptions":{"scoreFormat":"POINT_5"}}}}"""
        val user = api.getCurrentUser()
        user.id shouldBe 12
        user.name shouldBe "Name"
        user.mediaListOptions.scoreFormat shouldBe "POINT_5"
        requests.single().payload()["query"]!!.jsonPrimitive.content.contains("name") shouldBe true
    }

    @Test
    fun `Shikimori profile preserves id and exposes nickname`(): Unit = runBlocking {
        val api = ShikimoriApi(7, client, mockk())
        responseBody = """{"id":12,"nickname":"Name"}"""
        val user = api.getCurrentUser()
        user.id shouldBe 12
        user.nickname shouldBe "Name"
        requests.single().url.encodedPath shouldBe "/api/users/whoami"
    }

    @Test
    fun `MangaUpdates profile uses authenticated account endpoint`(): Unit = runBlocking {
        val api = MangaUpdatesApi(mockk(), client)
        responseBody = """{"username":"Name"}"""
        api.getCurrentUser().username shouldBe "Name"
        requests.single().url.encodedPath shouldBe "/v1/account/profile"
    }

    private fun Request.payload() = json.parseToJsonElement(Buffer().also { body!!.writeTo(it) }.readUtf8()).jsonObject

    private fun bangumiSubject(type: Int) = """{"id":42,"type":$type,"name_cn":"","name":"Title","platform":"漫画"}"""

    companion object {
        private val invalidIds = listOf("id:", "id:abc", "id:-1", "id:0", "id:9999999999999999999999999999")
        private const val ANILIST_RESULT = """{
            "data":{"Page":{"media":[{"id":42,"title":{"userPreferred":"Title"},"coverImage":{"large":""},
            "startDate":{},"staff":{"edges":[]},"studios":{"edges":[]}}]}}
        }"""
        private const val SHIKIMORI_RESULT = """[{
            "id":42,"name":"Title","chapters":10,"episodes":12,"image":{"preview":"/image.jpg"},
            "score":8.5,"url":"/mangas/42","status":"released","kind":"manga"
        }]"""
    }
}
