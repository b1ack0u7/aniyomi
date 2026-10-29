package tachiyomi.domain.download.service

import tachiyomi.core.common.preference.PreferenceStore

class DownloadPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun downloadOnlyOverWifi() = preferenceStore.getBoolean(
        "pref_download_only_over_wifi_key",
        true,
    )

    fun useExternalDownloader() = preferenceStore.getBoolean("use_external_downloader", false)

    fun externalDownloaderSelection() = preferenceStore.getString(
        "external_downloader_selection",
        "",
    )

    fun smartMangaDownloads() = preferenceStore.getBoolean("smart_manga_downloads", true)

    fun smartMangaRequestIntervalMs() = preferenceStore.getInt("smart_manga_request_interval_ms", 1500)

    fun smartMangaChapterDelaySeconds() = preferenceStore.getInt("smart_manga_chapter_delay_seconds", 10)

    fun smartMangaBatchSize() = preferenceStore.getInt("smart_manga_batch_size", 5)

    fun smartMangaBatchDelaySeconds() = preferenceStore.getInt("smart_manga_batch_delay_seconds", 120)

    fun smartMangaRetryInitialSeconds() = preferenceStore.getInt("smart_manga_retry_initial_seconds", 300)

    fun smartMangaRetryMaxSeconds() = preferenceStore.getInt("smart_manga_retry_max_seconds", 3600)

    fun applyMangaDownloadPreset(preset: MangaDownloadPreset) {
        smartMangaRequestIntervalMs().set(preset.requestIntervalMs)
        smartMangaChapterDelaySeconds().set(preset.chapterDelaySeconds)
        smartMangaBatchSize().set(preset.batchSize)
        smartMangaBatchDelaySeconds().set(preset.batchDelaySeconds)
        smartMangaRetryInitialSeconds().set(preset.retryInitialSeconds)
        smartMangaRetryMaxSeconds().set(preset.retryMaxSeconds)
        smartMangaDownloads().set(true)
    }

    fun restoreRecommendedMangaDownloads() {
        smartMangaDownloads().delete()
        smartMangaRequestIntervalMs().delete()
        smartMangaChapterDelaySeconds().delete()
        smartMangaBatchSize().delete()
        smartMangaBatchDelaySeconds().delete()
        smartMangaRetryInitialSeconds().delete()
        smartMangaRetryMaxSeconds().delete()
    }

    fun saveChaptersAsCBZ() = preferenceStore.getBoolean("save_chapter_as_cbz", true)

    fun splitTallImages() = preferenceStore.getBoolean("split_tall_images", true)

    fun autoDownloadWhileReading() = preferenceStore.getInt("auto_download_while_reading", 0)
    fun autoDownloadWhileWatching() = preferenceStore.getInt("auto_download_while_watching", 0)

    fun removeAfterReadSlots() = preferenceStore.getInt("remove_after_read_slots", -1)

    fun removeAfterMarkedAsRead() = preferenceStore.getBoolean(
        "pref_remove_after_marked_as_read_key",
        false,
    )

    fun removeBookmarkedChapters() = preferenceStore.getBoolean("pref_remove_bookmarked", false)

    fun downloadFillermarkedItems() = preferenceStore.getBoolean("pref_download_fillermarked", false)

    fun removeExcludeCategories() = preferenceStore.getStringSet(
        REMOVE_EXCLUDE_MANGA_CATEGORIES_PREF_KEY,
        emptySet(),
    )
    fun removeExcludeAnimeCategories() = preferenceStore.getStringSet(
        REMOVE_EXCLUDE_ANIME_CATEGORIES_PREF_KEY,
        emptySet(),
    )

    fun downloadNewChapters() = preferenceStore.getBoolean("download_new", false)
    fun downloadNewEpisodes() = preferenceStore.getBoolean("download_new_episode", false)

    fun downloadNewChapterCategories() = preferenceStore.getStringSet(
        DOWNLOAD_NEW_MANGA_CATEGORIES_PREF_KEY,
        emptySet(),
    )
    fun downloadNewEpisodeCategories() = preferenceStore.getStringSet(
        DOWNLOAD_NEW_ANIME_CATEGORIES_PREF_KEY,
        emptySet(),
    )

    fun downloadNewChapterCategoriesExclude() = preferenceStore.getStringSet(
        DOWNLOAD_NEW_MANGA_CATEGORIES_EXCLUDE_PREF_KEY,
        emptySet(),
    )
    fun downloadNewEpisodeCategoriesExclude() = preferenceStore.getStringSet(
        DOWNLOAD_NEW_ANIME_CATEGORIES_EXCLUDE_PREF_KEY,
        emptySet(),
    )

    fun numberOfDownloads() = preferenceStore.getInt("download_slots", 1)
    fun downloadSpeedLimit() = preferenceStore.getInt("download_speed_limit", 0)

    fun downloadNewUnreadChaptersOnly() = preferenceStore.getBoolean("download_new_unread_chapters_only", false)
    fun downloadNewUnseenEpisodesOnly() = preferenceStore.getBoolean("download_new_unread_episodes_only", false)

    companion object {
        private const val REMOVE_EXCLUDE_MANGA_CATEGORIES_PREF_KEY = "remove_exclude_categories"
        private const val REMOVE_EXCLUDE_ANIME_CATEGORIES_PREF_KEY = "remove_exclude_anime_categories"
        private const val DOWNLOAD_NEW_MANGA_CATEGORIES_PREF_KEY = "download_new_categories"
        private const val DOWNLOAD_NEW_ANIME_CATEGORIES_PREF_KEY = "download_new_anime_categories"
        private const val DOWNLOAD_NEW_MANGA_CATEGORIES_EXCLUDE_PREF_KEY = "download_new_categories_exclude"
        private const val DOWNLOAD_NEW_ANIME_CATEGORIES_EXCLUDE_PREF_KEY = "download_new_anime_categories_exclude"

        val mangaCategoryPreferenceKeys = setOf(
            REMOVE_EXCLUDE_MANGA_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_MANGA_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_MANGA_CATEGORIES_EXCLUDE_PREF_KEY,
        )
        val animeCategoryPreferenceKeys = setOf(
            REMOVE_EXCLUDE_ANIME_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_ANIME_CATEGORIES_PREF_KEY,
            DOWNLOAD_NEW_ANIME_CATEGORIES_EXCLUDE_PREF_KEY,
        )
        val categoryPreferenceKeys = mangaCategoryPreferenceKeys + animeCategoryPreferenceKeys
    }
}
