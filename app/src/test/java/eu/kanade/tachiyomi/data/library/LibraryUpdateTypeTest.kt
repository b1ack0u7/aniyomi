package eu.kanade.tachiyomi.data.library

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LibraryUpdateTypeTest {

    @TempDir
    lateinit var cacheDir: File

    @Test
    fun `entry tags distinguish libraries with identical database ids`() {
        LibraryUpdateType.MANGA.notificationTag(1) shouldNotBe LibraryUpdateType.ANIME.notificationTag(1)
    }

    @Test
    fun `entry tags distinguish long ids with identical hashes`() {
        val firstId = 1L
        val secondId = 1L shl 32
        firstId.hashCode() shouldBe secondId.hashCode()

        LibraryUpdateType.entries.forEach { type ->
            type.notificationTag(firstId) shouldNotBe type.notificationTag(secondId)
        }
    }

    @Test
    fun `error files preserve each library and each completed run`() {
        val mangaFirst = LibraryUpdateType.MANGA.createErrorFile(cacheDir).apply { writeText("first manga error") }
        val anime = LibraryUpdateType.ANIME.createErrorFile(cacheDir).apply { writeText("anime error") }
        val mangaSecond = LibraryUpdateType.MANGA.createErrorFile(cacheDir).apply { writeText("second manga error") }

        setOf(mangaFirst, anime, mangaSecond).size shouldBe 3
        mangaFirst.readText() shouldBe "first manga error"
        anime.readText() shouldBe "anime error"
        mangaSecond.readText() shouldBe "second manga error"
    }
}
