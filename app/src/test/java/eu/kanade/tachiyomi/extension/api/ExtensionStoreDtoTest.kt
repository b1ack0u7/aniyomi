package eu.kanade.tachiyomi.extension.api

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test

/**
 * The fixtures mimic what a repo serving the `index_v2` format sends: values matching the
 * protobuf default are omitted, and fields the app doesn't know about (such as `jarUrl`) are
 * present and must be skipped.
 */
class ExtensionStoreDtoTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        isLenient = true
    }

    @Test
    fun `decodes a protobuf index`() {
        val bytes = javaClass.getResourceAsStream("/extension/index_v2_sample.pb")!!.use { it.readBytes() }

        val store = ProtoBuf.decodeFromByteArray(ExtensionStoreDto.serializer(), bytes)

        store.name shouldBe "Test Store"
        store.badgeLabel shouldBe "TST"
        store.signingKey shouldBe "deadbeef"
        store.contact?.website shouldBe "https://test.store"
        store.contact?.discord shouldBe "https://discord.gg/test"
        store.assertSampleExtensions()
    }

    @Test
    fun `decodes a json index`() {
        val store = json.decodeFromString(ExtensionStoreDto.serializer(), JSON_INDEX)

        store.name shouldBe "Test Store"
        store.assertSampleExtensions()
    }

    private fun ExtensionStoreDto.assertSampleExtensions() {
        val extensions = extensionList!!.extensions
        extensions.size shouldBe 3

        val alpha = extensions[0]
        alpha.name shouldBe "Alpha"
        alpha.packageName shouldBe "test.ext.alpha"
        alpha.resources.apkUrl shouldBe "https://cdn.test/apk/alpha-v1.4.7.apk"
        alpha.resources.iconUrl shouldBe "https://cdn.test/icon/alpha.png"
        alpha.extensionLib shouldBe "1.4"
        alpha.versionCode shouldBe 7L
        alpha.versionName shouldBe "1.4.7"
        alpha.contentWarning shouldBe ExtensionContentWarning.SAFE
        alpha.sources.single().id shouldBe 12345L
        alpha.sources.single().language shouldBe "en"
        alpha.sources.single().homeUrl shouldBe "https://alpha.test"

        val beta = extensions[1]
        beta.extensionLib shouldBe "1.6"
        beta.contentWarning shouldBe ExtensionContentWarning.NSFW
        // 64 bit source ids must survive both encodings
        beta.sources.first().id shouldBe 6289731484943315811L
        beta.sources.map { it.language } shouldBe listOf("en", "es")

        // Every value of this one matches the protobuf default, so nothing but the set fields is sent
        val gamma = extensions[2]
        gamma.versionCode shouldBe 0L
        gamma.contentWarning shouldBe ExtensionContentWarning.UNSPECIFIED
        gamma.sources shouldBe emptyList()
        gamma.resources.iconUrl shouldBe ""
    }
}

private const val JSON_INDEX = """
{
  "name": "Test Store",
  "badgeLabel": "TST",
  "signingKey": "deadbeef",
  "contact": { "website": "https://test.store", "discord": "https://discord.gg/test" },
  "extensionList": {
    "extensions": [
      {
        "name": "Alpha",
        "packageName": "test.ext.alpha",
        "resources": {
          "apkUrl": "https://cdn.test/apk/alpha-v1.4.7.apk",
          "iconUrl": "https://cdn.test/icon/alpha.png",
          "jarUrl": "https://cdn.test/jar/alpha.jar"
        },
        "extensionLib": "1.4",
        "versionCode": "7",
        "versionName": "1.4.7",
        "contentWarning": "CONTENT_WARNING_SAFE",
        "sources": [
          { "id": "12345", "name": "Alpha", "language": "en", "homeUrl": "https://alpha.test" }
        ]
      },
      {
        "name": "Beta",
        "packageName": "test.ext.beta",
        "resources": {
          "apkUrl": "https://cdn.test/apk/beta-v1.6.2.apk",
          "iconUrl": "https://cdn.test/icon/beta.png",
          "jarUrl": "https://cdn.test/jar/beta.jar"
        },
        "extensionLib": "1.6",
        "versionCode": "2",
        "versionName": "1.6.2",
        "contentWarning": "CONTENT_WARNING_NSFW",
        "sources": [
          { "id": "6289731484943315811", "name": "Beta EN", "language": "en", "homeUrl": "https://beta.test" },
          { "id": "222", "name": "Beta ES", "language": "es", "homeUrl": "https://beta.test" }
        ]
      },
      {
        "name": "Gamma",
        "packageName": "test.ext.gamma",
        "resources": { "apkUrl": "https://cdn.test/apk/gamma-v1.4.0.apk" },
        "extensionLib": "1.4",
        "versionName": "1.4.0"
      }
    ]
  },
  "unknownField": "ignored"
}
"""
