package tachiyomi.domain.download.service

enum class MangaDownloadPreset(
    val requestIntervalMs: Int,
    val chapterDelaySeconds: Int,
    val batchSize: Int,
    val batchDelaySeconds: Int,
    val retryInitialSeconds: Int = 300,
    val retryMaxSeconds: Int = 3600,
) {
    FAST(750, 5, 8, 60),
    DEFAULT(1500, 10, 5, 120),
    SLOW(3000, 30, 3, 300, 900, 7200),
    ;

    companion object {
        fun matching(
            requestIntervalMs: Int,
            chapterDelaySeconds: Int,
            batchSize: Int,
            batchDelaySeconds: Int,
            retryInitialSeconds: Int,
            retryMaxSeconds: Int,
        ): MangaDownloadPreset? = entries.firstOrNull {
            it.requestIntervalMs == requestIntervalMs &&
                it.chapterDelaySeconds == chapterDelaySeconds &&
                it.batchSize == batchSize &&
                it.batchDelaySeconds == batchDelaySeconds &&
                it.retryInitialSeconds == retryInitialSeconds &&
                it.retryMaxSeconds == retryMaxSeconds
        }
    }
}
