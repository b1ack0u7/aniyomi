package eu.kanade.tachiyomi.data.download.manga

import android.content.SharedPreferences
import androidx.core.content.edit
import eu.kanade.tachiyomi.network.CloudflareChallengeException
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.SourceRequestPausedException
import eu.kanade.tachiyomi.network.sourceErrorDeadline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.download.service.MangaDownloadPolicy
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

internal class MangaDownloadLimiter(
    private val store: SharedPreferences,
    private val preferences: DownloadPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val nextRequest = ConcurrentHashMap<Long, Long>()

    val enabled: Boolean get() = preferences.smartMangaDownloads().get()

    private fun policy() = MangaDownloadPolicy(
        preferences.smartMangaRequestIntervalMs().get(),
        preferences.smartMangaChapterDelaySeconds().get(),
        preferences.smartMangaBatchSize().get(),
        preferences.smartMangaBatchDelaySeconds().get(),
        preferences.smartMangaRetryInitialSeconds().get(),
        preferences.smartMangaRetryMaxSeconds().get(),
    )

    fun forbidden(source: Long): Boolean = store.getBoolean("forbidden_$source", false)

    fun blocked(source: Long): Boolean = forbidden(source) || store.getBoolean("challenge_$source", false)

    fun deadline(source: Long): Long = store.getLong("until_$source", 0)

    fun ready(source: Long): Boolean = !blocked(source) && deadline(source) <= now()

    fun resumeChallenges() = synchronized(store) {
        store.edit {
            store.all.keys.filter { it.startsWith("challenge_") || it.startsWith("forbidden_") }
                .forEach { remove(it) }
        }
    }

    suspend fun beforeRequest(source: Long) {
        if (!ready(source)) throw SourceDownloadWaiting()
        if (!enabled) return
        val wait = (nextRequest[source] ?: 0) - now()
        if (wait > 0) delay(wait)
        if (!ready(source)) throw SourceDownloadWaiting()
        val recovering = store.getInt("failures_$source", 0) > 0
        nextRequest[source] = now() + policy().requestDelayMillis(recovering)
    }

    fun completed(source: Long) = synchronized(store) {
        if (!enabled) return@synchronized
        val policy = policy()
        val recovering = store.getInt("failures_$source", 0) > 0
        val successes = store.getInt("successes_$source", 0) + 1
        val chapters = store.getInt("batch_$source", 0) + 1
        val batchFinished = chapters >= policy.batchSize
        val wait = maxOf(
            policy.chapterDelayMillis(recovering),
            if (batchFinished) policy.batchDelaySeconds * 1_000L else 0,
        )
        store.edit {
            putLong("until_$source", maxOf(store.getLong("until_$source", 0L), now() + wait))
            putInt("batch_$source", if (batchFinished) 0 else chapters)
            putInt("successes_$source", successes.coerceAtMost(3))
            if (successes >= 3) putInt("failures_$source", 0)
        }
    }

    fun defer(source: Long, error: Throwable, networkFailure: Boolean = false): Boolean = synchronized(store) {
        if (error is SourceDownloadWaiting) return@synchronized true
        if (error is SourceRequestPausedException) return@synchronized true
        val causes = generateSequence(error) { it.cause }.take(16).toList()
        if (causes.any { it is CancellationException }) return@synchronized false
        if (causes.any { it is CloudflareChallengeException }) {
            store.edit { putBoolean("challenge_$source", true) }
            return@synchronized true
        }
        val http = causes.filterIsInstance<HttpException>().firstOrNull()
        if (http?.code == 403) {
            store.edit { putBoolean("forbidden_$source", true) }
            return@synchronized true
        }
        if (http != null && http.code != 429 && http.code !in 500..599) return@synchronized false
        if (http == null &&
            causes.none { it is SourceNetworkException } &&
            (!networkFailure || causes.none { it is IOException })
        ) {
            return@synchronized false
        }
        val failures = (store.getInt("failures_$source", 0) + 1).coerceAtMost(30)
        val now = now()
        val wait = maxOf(
            policy().retryDelayMillis(failures),
            MangaDownloadPolicy.parseRetryAfter(http?.retryAfter, now) ?: 0,
        )
        store.edit {
            val until = if (wait > Long.MAX_VALUE - now) Long.MAX_VALUE else now + wait
            putLong("until_$source", maxOf(store.getLong("until_$source", 0L), until))
            putLong("error_until_$source", maxOf(store.sourceErrorDeadline(source), until))
            if (http?.code == 429) {
                putLong("rate_limit_until_$source", maxOf(store.getLong("rate_limit_until_$source", 0L), until))
            } else if (store.getLong("rate_limit_until_$source", 0L) <= now) {
                remove("rate_limit_until_$source")
            }
            putInt("failures_$source", failures)
            putInt("successes_$source", 0)
        }
        true
    }

    fun canRetryImage(error: Throwable, attempt: Long): Boolean {
        if (enabled || attempt >= 3) return false
        return generateSequence(error) { it.cause }.take(16).none {
            it is SourceDownloadWaiting || it is SourceRequestPausedException || it is CloudflareChallengeException ||
                it is CancellationException || it is HttpException && (it.code == 403 || it.code == 429)
        }
    }
}

internal class SourceDownloadWaiting : IOException()

internal class SourceNetworkException(cause: IOException) : IOException(cause)
