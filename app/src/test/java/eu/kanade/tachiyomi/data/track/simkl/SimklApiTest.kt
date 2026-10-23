package eu.kanade.tachiyomi.data.track.simkl

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

class SimklApiTest {

    private val originalInjekt = Injekt

    @BeforeEach
    fun setUp() {
        Injekt = InjektScope(DefaultRegistrar())
        Injekt.addSingleton(Json { ignoreUnknownKeys = true })
    }

    @AfterEach
    fun tearDown() {
        Injekt = originalInjekt
    }

    @Test
    fun `profile keeps numeric authentication id separate from display name`(): Unit = runBlocking {
        var request: Request? = null
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            request = chain.request()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """{"account":{"id":42,"timezone":"UTC"},"user":{"name":"New name","age":28}}"""
                        .toResponseBody(),
                )
                .build()
        }.build()

        val profile = SimklApi(client, mockk()).getCurrentUser()

        request!!.method shouldBe "GET"
        request!!.url.encodedPath shouldBe "/users/settings"
        profile.account.id shouldBe 42
        profile.user.name shouldBe "New name"
    }
}
