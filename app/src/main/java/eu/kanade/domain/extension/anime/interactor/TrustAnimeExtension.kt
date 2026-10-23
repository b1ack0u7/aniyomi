package eu.kanade.domain.extension.anime.interactor

import android.content.pm.PackageInfo
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import mihon.domain.extensionrepo.anime.repository.AnimeExtensionRepoRepository
import tachiyomi.core.common.preference.getAndSet

class TrustAnimeExtension(
    private val animeExtensionRepoRepository: AnimeExtensionRepoRepository,
    private val preferences: SourcePreferences,
) {

    suspend fun isTrusted(pkgInfo: PackageInfo, fingerprints: List<String>): Boolean {
        val trustedFingerprints = animeExtensionRepoRepository.getAll().map { it.signingKeyFingerprint }.toHashSet()
        val key = "${pkgInfo.packageName}:${PackageInfoCompat.getLongVersionCode(pkgInfo)}:${fingerprints.last()}"
        return trustedFingerprints.any { fingerprints.contains(it) } || key in preferences.trustedExtensions().get()
    }

    fun trust(pkgName: String, versionCode: Long, signatureHash: String) {
        preferences.trustedExtensions().getAndSet { exts ->
            // Remove previously trusted versions
            val removed = exts.filterNot { it.startsWith("$pkgName:") }.toMutableSet()

            removed.also { it += "$pkgName:$versionCode:$signatureHash" }
        }
    }

    fun changes(): Flow<Unit> = merge(
        animeExtensionRepoRepository.subscribeAll()
            .map { repos -> repos.mapTo(HashSet()) { it.signingKeyFingerprint } }
            .distinctUntilChanged()
            .drop(1),
        preferences.trustedExtensions().changes().distinctUntilChanged().drop(1),
    ).map {}

    fun revokeAll() {
        preferences.trustedExtensions().delete()
    }
}
