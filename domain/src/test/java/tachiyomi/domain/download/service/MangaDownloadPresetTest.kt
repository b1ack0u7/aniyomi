package tachiyomi.domain.download.service

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

class MangaDownloadPresetTest {

    @Test
    fun `default profile matches fresh preferences and runtime defaults`() {
        val preferences = preferences()
        preferences.selectedPreset() shouldBe MangaDownloadPreset.DEFAULT
        val policy = MangaDownloadPolicy()
        val preset = MangaDownloadPreset.DEFAULT
        preset.requestIntervalMs shouldBe policy.requestIntervalMs
        preset.chapterDelaySeconds shouldBe policy.chapterDelaySeconds
        preset.batchSize shouldBe policy.batchSize
        preset.batchDelaySeconds shouldBe policy.batchDelaySeconds
        preset.retryInitialSeconds shouldBe policy.retryInitialSeconds
        preset.retryMaxSeconds shouldBe policy.retryMaxSeconds
    }

    @Test
    fun `selecting a profile replaces all manual values and enables smart downloads`() {
        val preferences = preferences()
        MangaDownloadPreset.entries.forEach { preset ->
            preferences.smartMangaDownloads().set(false)
            preferences.smartMangaRetryInitialSeconds().set(123)
            preferences.smartMangaRequestIntervalMs().set(999)
            preferences.applyMangaDownloadPreset(preset)
            preferences.selectedPreset() shouldBe preset
            preferences.smartMangaDownloads().get() shouldBe true
        }
    }

    @Test
    fun `manual changes become custom and restoring selects default again`() {
        val preferences = preferences()
        preferences.applyMangaDownloadPreset(MangaDownloadPreset.FAST)
        preferences.smartMangaBatchSize().set(7)
        preferences.selectedPreset() shouldBe null
        preferences.applyMangaDownloadPreset(MangaDownloadPreset.SLOW)
        preferences.smartMangaRetryMaxSeconds().set(8000)
        preferences.selectedPreset() shouldBe null
        preferences.smartMangaDownloads().set(false)
        preferences.restoreRecommendedMangaDownloads()
        preferences.selectedPreset() shouldBe MangaDownloadPreset.DEFAULT
        preferences.smartMangaDownloads().get() shouldBe true
    }

    @Test
    fun `profiles stay within supported limits with ordered pacing`() {
        MangaDownloadPreset.entries.forEach { preset ->
            val policy = MangaDownloadPolicy(
                preset.requestIntervalMs,
                preset.chapterDelaySeconds,
                preset.batchSize,
                preset.batchDelaySeconds,
                preset.retryInitialSeconds,
                preset.retryMaxSeconds,
            )
            MangaDownloadPreset.matching(
                policy.requestIntervalMs,
                policy.chapterDelaySeconds,
                policy.batchSize,
                policy.batchDelaySeconds,
                policy.retryInitialSeconds,
                policy.retryMaxSeconds,
            ) shouldBe preset
        }
        val fast = MangaDownloadPreset.FAST
        val normal = MangaDownloadPreset.DEFAULT
        val slow = MangaDownloadPreset.SLOW
        (fast.requestIntervalMs < normal.requestIntervalMs) shouldBe true
        (normal.requestIntervalMs < slow.requestIntervalMs) shouldBe true
        (fast.chapterDelaySeconds < normal.chapterDelaySeconds && normal.chapterDelaySeconds < slow.chapterDelaySeconds)
            .shouldBe(true)
        (fast.batchSize > normal.batchSize && normal.batchSize > slow.batchSize) shouldBe true
        (fast.batchDelaySeconds < normal.batchDelaySeconds && normal.batchDelaySeconds < slow.batchDelaySeconds)
            .shouldBe(true)
    }

    private fun DownloadPreferences.selectedPreset() = MangaDownloadPreset.matching(
        smartMangaRequestIntervalMs().get(),
        smartMangaChapterDelaySeconds().get(),
        smartMangaBatchSize().get(),
        smartMangaBatchDelaySeconds().get(),
        smartMangaRetryInitialSeconds().get(),
        smartMangaRetryMaxSeconds().get(),
    )

    private fun preferences(): DownloadPreferences {
        val values = mutableMapOf<String, Any>()
        val store = mockk<PreferenceStore>()
        every { store.getInt(any(), any()) } answers {
            val key = firstArg<String>()
            val default = secondArg<Int>()
            mockk<Preference<Int>> {
                every { get() } answers { values[key] as? Int ?: default }
                every { set(any()) } answers { values[key] = firstArg<Int>() }
                every { delete() } answers {
                    values.remove(key)
                    Unit
                }
            }
        }
        every { store.getBoolean(any(), any()) } answers {
            val key = firstArg<String>()
            val default = secondArg<Boolean>()
            mockk<Preference<Boolean>> {
                every { get() } answers { values[key] as? Boolean ?: default }
                every { set(any()) } answers { values[key] = firstArg<Boolean>() }
                every { delete() } answers {
                    values.remove(key)
                    Unit
                }
            }
        }
        return DownloadPreferences(store)
    }
}
