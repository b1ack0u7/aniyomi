package eu.kanade.tachiyomi.data.track

import androidx.annotation.CallSuper
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import logcat.LogPriority
import logcat.logcat
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy

abstract class BaseTracker(
    override val id: Long,
    override val name: String,
) : Tracker {

    val trackPreferences: TrackPreferences by injectLazy()
    val networkService: NetworkHelper by injectLazy()

    override val client: OkHttpClient
        get() = networkService.client

    // Application and remote support for reading dates
    override val supportsReadingDates: Boolean = false

    override val supportsPrivateTracking: Boolean = false

    private val refreshMutex = Mutex()
    private val refreshStateLock = Any()
    private var refreshJob: Job? = null
    private val isRefreshing = MutableStateFlow(false)
    private val refreshResult = MutableSharedFlow<RefreshResult>(extraBufferCapacity = 1)

    final override val isRefreshingFlow = isRefreshing.asStateFlow()
    final override val refreshResultFlow = refreshResult.asSharedFlow()

    final override suspend fun refreshUser(): Unit = coroutineScope {
        if (!refreshMutex.tryLock()) return@coroutineScope
        try {
            val job = currentCoroutineContext()[Job]
            val started = synchronized(refreshStateLock) {
                if (isLoggedIn) {
                    refreshJob = job
                    isRefreshing.value = true
                    true
                } else {
                    false
                }
            }
            if (!started) return@coroutineScope
            try {
                updateUserConfig()
                currentCoroutineContext().ensureActive()
                refreshResult.emit(RefreshResult.Success)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR) { "Failed to update user config id=$id: ${e.stackTraceToString()}" }
                refreshResult.emit(RefreshResult.Error(e.message ?: e.toString()))
            } finally {
                isRefreshing.value = false
                synchronized(refreshStateLock) { refreshJob = null }
            }
        } finally {
            refreshMutex.unlock()
        }
    }

    protected open suspend fun updateUserConfig() = Unit

    protected suspend fun saveUserConfig(update: () -> Unit) {
        val context = currentCoroutineContext()
        synchronized(refreshStateLock) {
            context.ensureActive()
            update()
        }
    }

    @CallSuper
    override fun logout() {
        synchronized(refreshStateLock) {
            refreshJob?.cancel()
            trackPreferences.setCredentials(this, "", "")
            trackPreferences.trackDisplayUsername(this).delete()
        }
    }

    override val isLoggedIn: Boolean
        get() = getUsername().isNotEmpty() &&
            getPassword().isNotEmpty()

    override val isLoggedInFlow: Flow<Boolean> by lazy {
        combine(
            trackPreferences.trackUsername(this).changes(),
            trackPreferences.trackPassword(this).changes(),
        ) { username, password ->
            username.isNotEmpty() && password.isNotEmpty()
        }
    }

    override fun getUsername() = trackPreferences.trackUsername(this).get()

    override fun getDisplayUsername() = trackPreferences.trackDisplayUsername(this).get()

    override fun saveDisplayUsername(username: String) {
        trackPreferences.trackDisplayUsername(this).set(username)
    }

    override fun getPassword() = trackPreferences.trackPassword(this).get()

    override fun saveCredentials(username: String, password: String) {
        synchronized(refreshStateLock) {
            refreshJob?.cancel()
            trackPreferences.setCredentials(this, username, password)
        }
    }
}

sealed interface RefreshResult {
    data object Success : RefreshResult
    data class Error(val msg: String) : RefreshResult
}
