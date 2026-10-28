package eu.kanade.tachiyomi.network

import android.content.SharedPreferences
import androidx.core.content.edit
import eu.kanade.tachiyomi.data.library.LibraryUpdateType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tachiyomi.core.common.util.lang.causeChain
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.download.service.MangaDownloadPolicy
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

class SourceRequestCoordinator(
    private val mangaStore: SharedPreferences,
    private val animeStore: SharedPreferences,
    private val preferences: DownloadPreferences,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val locks = ConcurrentHashMap<Pair<LibraryUpdateType, Long>, Mutex>()
    private val nextRequests = ConcurrentHashMap<Pair<LibraryUpdateType, Long>, Long>()

    fun forbidden(type: LibraryUpdateType, source: Long): Boolean = store(type).getBoolean("forbidden_$source", false)

    fun blocked(type: LibraryUpdateType, source: Long): Boolean =
        forbidden(type, source) || store(type).getBoolean("challenge_$source", false)

    fun deadline(type: LibraryUpdateType, source: Long): Long = store(type).getLong("until_$source", 0L)

    fun mangaRestriction(source: Long): SourceAccessRestriction? = synchronized(mangaStore) {
        val forbidden = forbidden(LibraryUpdateType.MANGA, source)
        val rateLimited = mangaStore.getLong("rate_limit_until_$source", 0L) > 0L
        val retryAt = retryDeadline(LibraryUpdateType.MANGA, source)
        when {
            forbidden && rateLimited -> SourceAccessRestriction.ForbiddenAndRateLimited(retryAt)
            forbidden -> SourceAccessRestriction.Forbidden(retryAt.takeIf { it > 0L })
            rateLimited -> SourceAccessRestriction.RateLimited(retryAt)
            else -> null
        }
    }

    private fun retryDeadline(type: LibraryUpdateType, source: Long): Long {
        val store = store(type)
        val rateLimitUntil = store.getLong("rate_limit_until_$source", 0L)
        return maxOf(
            store.sourceErrorDeadline(source),
            rateLimitUntil,
            if (rateLimitUntil > 0L) deadline(type, source) else 0L,
        )
    }

    fun observeMangaRestriction(source: Long) = callbackFlow<SourceAccessRestriction?> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key.endsWith("_$source")) trySend(mangaRestriction(source))
        }
        mangaStore.registerOnSharedPreferenceChangeListener(listener)
        trySend(mangaRestriction(source))
        awaitClose { mangaStore.unregisterOnSharedPreferenceChangeListener(listener) }
    }.conflate().distinctUntilChanged()

    fun resumeChallenges(type: LibraryUpdateType) {
        val store = store(type)
        synchronized(store) {
            store.edit {
                store.all.keys.filter { it.startsWith("challenge_") }.forEach { remove(it) }
            }
        }
    }

    fun resumeForbidden(type: LibraryUpdateType, source: Long): Boolean {
        val store = store(type)
        return synchronized(store) {
            if (retryDeadline(type, source) > now()) return@synchronized false
            if (forbidden(type, source)) store.edit { remove("forbidden_$source") }
            true
        }
    }

    suspend fun <T> withReaderRequest(source: Long, block: suspend () -> T): T {
        checkReady(LibraryUpdateType.MANGA, source, reader = true)
        return execute(LibraryUpdateType.MANGA, source, block)
    }

    suspend fun <T> withRequest(type: LibraryUpdateType, source: Long, block: suspend () -> T): T {
        val key = type to source
        return locks.getOrPut(key) { Mutex() }.withLock {
            checkReady(type, source)
            val delayMillis = ((nextRequests[key] ?: 0L) - now()).coerceAtLeast(0L)
            if (delayMillis > 0L) delay(delayMillis)
            checkReady(type, source)
            val recovering = store(type).getInt("failures_$source", 0) > 0 ||
                store(type).getInt("request_failures_$source", 0) > 0
            val requestDelay = if (
                type == LibraryUpdateType.MANGA && !preferences.smartMangaDownloads().get() && !recovering
            ) {
                0L
            } else {
                policy(type).requestDelayMillis(recovering)
            }
            nextRequests[key] = deadlineAfter(now(), requestDelay)
            execute(type, source, block)
        }
    }

    private suspend fun <T> execute(type: LibraryUpdateType, source: Long, block: suspend () -> T): T = try {
        block().also { recordSuccess(type, source) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        recordFailure(type, source, e)
        throw e
    }

    private fun checkReady(type: LibraryUpdateType, source: Long, reader: Boolean = false) {
        if (forbidden(type, source)) {
            throw SourceRequestPausedException(
                retryAt = retryDeadline(type, source).takeIf { it > 0L },
                forbidden = true,
                rateLimited = store(type).getLong("rate_limit_until_$source", 0L) > 0L,
            )
        }
        if (blocked(type, source)) throw SourceRequestPausedException()
        val deadline = if (reader) store(type).sourceErrorDeadline(source) else deadline(type, source)
        if (deadline > now()) {
            throw SourceRequestPausedException(
                retryAt = deadline,
                rateLimited = store(type).getLong("rate_limit_until_$source", 0L) > 0L,
            )
        }
    }

    private fun recordFailure(type: LibraryUpdateType, source: Long, error: Throwable) {
        val causes = error.causeChain()
        if (causes.any { it is CancellationException }) return
        val store = store(type)
        synchronized(store) {
            val http = causes.filterIsInstance<HttpException>().firstOrNull()
            if (causes.any { it is CloudflareChallengeException }) {
                store.edit { putBoolean("challenge_$source", true) }
            } else if (type == LibraryUpdateType.MANGA && http?.code == 403) {
                store.edit { putBoolean("forbidden_$source", true) }
            } else if (isRetryable(error)) {
                val failures = maxOf(
                    store.getInt("request_failures_$source", 0),
                    store.getInt("failures_$source", 0),
                ).coerceIn(0, 29) + 1
                val now = now()
                val wait = maxOf(
                    policy(type).retryDelayMillis(failures),
                    MangaDownloadPolicy.parseRetryAfter(http?.retryAfter, now) ?: 0L,
                )
                store.edit {
                    putLong("until_$source", maxOf(deadline(type, source), deadlineAfter(now, wait)))
                    putLong("error_until_$source", maxOf(store.sourceErrorDeadline(source), deadlineAfter(now, wait)))
                    if (http?.code == 429) {
                        putLong(
                            "rate_limit_until_$source",
                            maxOf(store.getLong("rate_limit_until_$source", 0L), deadlineAfter(now, wait)),
                        )
                    } else if (store.getLong("rate_limit_until_$source", 0L) <= now) {
                        remove("rate_limit_until_$source")
                    }
                    putInt("request_failures_$source", failures)
                    putInt("request_successes_$source", 0)
                }
            }
        }
    }

    private fun recordSuccess(type: LibraryUpdateType, source: Long) {
        val store = store(type)
        synchronized(store) {
            if (blocked(type, source) || store.sourceErrorDeadline(source) > now()) return
            if (store.getLong("rate_limit_until_$source", 0L) > 0L) {
                store.edit { remove("rate_limit_until_$source") }
            }
            if (store.getInt("request_failures_$source", 0) == 0) return
            val successes = store.getInt("request_successes_$source", 0) + 1
            store.edit {
                putInt("request_successes_$source", successes.coerceAtMost(3))
                if (successes >= 3) putInt("request_failures_$source", 0)
            }
        }
    }

    private fun store(type: LibraryUpdateType) = when (type) {
        LibraryUpdateType.MANGA -> mangaStore
        LibraryUpdateType.ANIME -> animeStore
    }

    private fun policy(type: LibraryUpdateType): MangaDownloadPolicy = when (type) {
        LibraryUpdateType.ANIME -> MangaDownloadPolicy()
        LibraryUpdateType.MANGA -> MangaDownloadPolicy(
            preferences.smartMangaRequestIntervalMs().get(),
            preferences.smartMangaChapterDelaySeconds().get(),
            preferences.smartMangaBatchSize().get(),
            preferences.smartMangaBatchDelaySeconds().get(),
            preferences.smartMangaRetryInitialSeconds().get(),
            preferences.smartMangaRetryMaxSeconds().get(),
        )
    }

    companion object {
        fun isRetryable(error: Throwable): Boolean {
            val causes = error.causeChain()
            if (causes.any { it is CancellationException || it is CloudflareChallengeException }) return false
            val http = causes.filterIsInstance<HttpException>().firstOrNull()
            if (http != null) return http.code == 429 || http.code in 500..599
            return causes.any {
                it is UnknownHostException ||
                    it is ConnectException ||
                    it is SocketException ||
                    it is InterruptedIOException
            }
        }

        private fun deadlineAfter(now: Long, delayMillis: Long): Long =
            if (now > Long.MAX_VALUE - delayMillis) Long.MAX_VALUE else now + delayMillis
    }
}

internal fun SharedPreferences.sourceErrorDeadline(source: Long): Long {
    val legacyFailure = getInt("failures_$source", 0) > 0 || getInt("request_failures_$source", 0) > 0
    return getLong("error_until_$source", if (legacyFailure) getLong("until_$source", 0L) else 0L)
}

class SourceRequestPausedException(
    val retryAt: Long? = null,
    val forbidden: Boolean = false,
    val rateLimited: Boolean = false,
) : Exception(
    if (forbidden) {
        "Source paused after HTTP 403. Resume this source manually when ready to retry."
    } else if (retryAt == null) {
        "Source paused: resolve the Cloudflare challenge in WebView and retry manually"
    } else {
        "Source paused until $retryAt"
    },
)
