package eu.kanade.tachiyomi.network.interceptor

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

// Keep one result for overlapping requests, and release host state when the batch finishes.
internal class CloudflareChallengeCoordinator {
    private val hosts = mutableMapOf<String, Host>()

    fun <T> withHost(host: String, block: (Challenge) -> T): T {
        val entry = synchronized(hosts) {
            hosts.getOrPut(host) { Host() }.also { it.users++ }
        }
        try {
            return block(Challenge(entry, entry.generation))
        } finally {
            synchronized(hosts) {
                if (--entry.users == 0) hosts.remove(host)
            }
        }
    }

    internal class Host {
        val lock = ReentrantLock()
        var users = 0

        @Volatile
        var generation = 0L
        var failure: Exception? = null
    }

    class Challenge internal constructor(private val host: Host, private val generation: Long) {
        fun resolve(isCanceled: () -> Boolean, block: () -> Unit) {
            try {
                while (!host.lock.tryLock(100, TimeUnit.MILLISECONDS)) {
                    if (isCanceled()) throw IOException("Canceled")
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException(e)
            }
            try {
                if (isCanceled()) throw IOException("Canceled")
                if (host.generation != generation) {
                    host.failure?.let { throw it }
                    return
                }
                var completed = true
                try {
                    block()
                    host.failure = null
                } catch (e: Exception) {
                    completed = !isCanceled() && !Thread.currentThread().isInterrupted
                    if (completed) host.failure = e
                    throw e
                } finally {
                    if (completed) host.generation++
                }
            } finally {
                host.lock.unlock()
            }
        }
    }
}
