package eu.kanade.tachiyomi.data.track.kitsu

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

class KitsuApiTest {

    private val originalInjekt = Injekt
    private val requests = mutableListOf<Request>()
    private var response = ""
    private val client = OkHttpClient.Builder().addInterceptor { chain ->
        requests += chain.request()
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(response.toResponseBody())
            .build()
    }.build()
    private lateinit var api: KitsuApi

    @BeforeEach
    fun setUp() {
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        api = KitsuApi(client, mockk())
    }

    @AfterEach
    fun tearDown() {
        Injekt = originalInjekt
    }

    @Test
    fun `numeric manga lookup uses media id and maps optional metadata`(): Unit = runBlocking {
        response = """{"data":[{"id":"42","attributes":{"canonicalTitle":"Manga","chapterCount":12}}]}"""
        val result = api.getMangaDetails("42")!!
        requests.single().url.encodedPath shouldBe "/api/edge/manga"
        requests.single().url.queryParameter("filter[id]") shouldBe "42"
        result.remote_id shouldBe 42L
        result.title shouldBe "Manga"
        result.total_chapters shouldBe 12L
        result.tracking_url shouldBe "https://kitsu.app/manga/42"
        result.summary shouldBe ""
    }

    @Test
    fun `anime slug lookup safely encodes query and uses anime endpoint`(): Unit = runBlocking {
        response = """{"data":[{"id":"7","attributes":{"canonicalTitle":"Anime","episodeCount":24}}]}"""
        val result = api.getAnimeDetails("cowboy-bebop")!!
        requests.single().url.encodedPath shouldBe "/api/edge/anime"
        requests.single().url.queryParameter("filter[slug]") shouldBe "cowboy-bebop"
        result.remote_id shouldBe 7L
        result.total_episodes shouldBe 24L
        result.tracking_url shouldBe "https://kitsu.app/anime/7"
    }

    @Test
    fun `blank lookup makes no request and absent results stay empty`(): Unit = runBlocking {
        api.getMangaDetails("") shouldBe null
        api.getAnimeDetails(" ") shouldBe null
        requests.size shouldBe 0
        response = """{"data":[]}"""
        api.getMangaDetails("999999") shouldBe null
        api.getAnimeDetails("missing-title") shouldBe null
    }

    @Test
    fun `manga direct lookup continues to exclude novels`(): Unit = runBlocking {
        response = """{"data":[{"id":"42","attributes":{"canonicalTitle":"Novel","subtype":"novel"}}]}"""
        api.getMangaDetails("42") shouldBe null
    }

    @Test
    fun `profile requests rating system and account name in one authenticated request`(): Unit = runBlocking {
        response = """{"data":{"currentAccount":{"id":"5","ratingSystem":"REGULAR","profile":{"name":"Name"}}}}"""
        val account = api.getCurrentUser()
        requests.single().url.encodedPath shouldBe "/api/graphql"
        requests.single().method shouldBe "POST"
        account.id shouldBe "5"
        account.profile.name shouldBe "Name"
        KitsuRatingSystem.fromPreference(account.ratingSystem) shouldBe KitsuRatingSystem.REGULAR
    }

    @Test
    fun `unknown or absent remote rating falls back without rejecting valid profile`(): Unit = runBlocking {
        for (ratingField in listOf("", "\"ratingSystem\":null,", "\"ratingSystem\":\"FUTURE_SCALE\",")) {
            response = """{"data":{"currentAccount":{"id":"5",$ratingField"profile":{"name":"Name"}}}}"""
            KitsuRatingSystem.fromPreference(api.getCurrentUser().ratingSystem) shouldBe KitsuRatingSystem.ADVANCED
        }
    }

    @Test
    fun `graphql errors cannot silently replace existing account configuration`() {
        response = """{"data":null,"errors":[{"message":"Unauthenticated"}]}"""
        assertThrows<SerializationException> { runBlocking { api.getCurrentUser() } }
    }
}
