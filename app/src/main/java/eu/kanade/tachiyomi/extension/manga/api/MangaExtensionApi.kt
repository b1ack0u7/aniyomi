package eu.kanade.tachiyomi.extension.manga.api

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.api.ExtensionContentWarning
import eu.kanade.tachiyomi.extension.api.ExtensionStoreApi
import eu.kanade.tachiyomi.extension.api.ExtensionStoreExtensionDto
import eu.kanade.tachiyomi.extension.api.ExtensionStoreSourceDto
import eu.kanade.tachiyomi.extension.manga.MangaExtensionManager
import eu.kanade.tachiyomi.extension.manga.model.MangaExtension
import eu.kanade.tachiyomi.extension.manga.model.MangaLoadResult
import eu.kanade.tachiyomi.extension.manga.util.MangaExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import logcat.LogPriority
import mihon.domain.extensionrepo.manga.interactor.GetMangaExtensionRepo
import mihon.domain.extensionrepo.manga.interactor.UpdateMangaExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.service.ExtensionRepoService
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import kotlin.time.Duration.Companion.days

internal class MangaExtensionApi {

    private val networkService: NetworkHelper by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()
    private val getExtensionRepo: GetMangaExtensionRepo by injectLazy()
    private val updateExtensionRepo: UpdateMangaExtensionRepo by injectLazy()
    private val extensionManager: MangaExtensionManager by injectLazy()
    private val extensionRepoService: ExtensionRepoService by injectLazy()
    private val json: Json by injectLazy()

    private val storeApi = ExtensionStoreApi()

    private val lastExtCheck: Preference<Long> by lazy {
        preferenceStore.getLong("last_ext_check", 0)
    }

    suspend fun findExtensions(): List<MangaExtension.Available> {
        return withIOContext {
            getExtensionRepo.getAll()
                .map { async { getExtensions(it) } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun getExtensions(extRepo: ExtensionRepo): List<MangaExtension.Available> {
        val repoBaseUrl = extRepo.baseUrl
        return try {
            val indexUrl = extensionRepoService.fetchIndexUrl(repoBaseUrl)
            if (indexUrl != null) {
                storeApi.findExtensions(indexUrl).toAvailableExtensions(repoBaseUrl)
            } else {
                getLegacyExtensions(repoBaseUrl)
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to get extensions from $repoBaseUrl" }
            emptyList()
        }
    }

    private suspend fun getLegacyExtensions(repoBaseUrl: String): List<MangaExtension.Available> {
        val response = networkService.client
            .newCall(GET("$repoBaseUrl/index.min.json"))
            .awaitSuccess()

        return with(json) {
            response
                .parseAs<List<ExtensionJsonObject>>()
                .toExtensions(repoBaseUrl)
        }
    }

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<MangaExtension.Installed>? {
        // Limit checks to once a day at most
        if (fromAvailableExtensionList &&
            Instant.now().toEpochMilli() < lastExtCheck.get() + 1.days.inWholeMilliseconds
        ) {
            return null
        }

        // Update extension repo details
        updateExtensionRepo.awaitAll()

        val extensions = if (fromAvailableExtensionList) {
            extensionManager.availableExtensionsFlow.value
        } else {
            findExtensions().also { lastExtCheck.set(Instant.now().toEpochMilli()) }
        }

        val installedExtensions = MangaExtensionLoader.loadMangaExtensions(context)
            .filterIsInstance<MangaLoadResult.Success>()
            .map { it.extension }

        val extensionsWithUpdate = mutableListOf<MangaExtension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
            val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
            val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
            val hasUpdate = hasUpdatedVer || hasUpdatedLib
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
        }

        return extensionsWithUpdate
    }

    private fun List<ExtensionStoreExtensionDto>.toAvailableExtensions(
        repoUrl: String,
    ): List<MangaExtension.Available> {
        return this
            .filter { it.packageName.isNotBlank() && it.resources.apkUrl.isNotBlank() }
            .filter { it.extractLibVersion() in MangaExtensionLoader.SUPPORTED_LIB_VERSIONS }
            .map {
                val langs = it.sources.map(ExtensionStoreSourceDto::language).toSet()
                MangaExtension.Available(
                    name = it.name,
                    pkgName = it.packageName,
                    versionName = it.versionName,
                    versionCode = it.versionCode,
                    libVersion = it.extractLibVersion(),
                    lang = langs.singleOrNull() ?: "all",
                    isNsfw = it.contentWarning >= ExtensionContentWarning.MIXED,
                    sources = it.sources.map(storeSourceMapper),
                    apkUrl = it.resources.apkUrl,
                    iconUrl = it.resources.iconUrl,
                    repoUrl = repoUrl,
                )
            }
    }

    private fun List<ExtensionJsonObject>.toExtensions(repoUrl: String): List<MangaExtension.Available> {
        return this
            .filter { it.extractLibVersion() in MangaExtensionLoader.SUPPORTED_LIB_VERSIONS }
            .map {
                MangaExtension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    sources = it.sources?.map(extensionSourceMapper).orEmpty(),
                    apkUrl = "$repoUrl/apk/${it.apk}",
                    iconUrl = "$repoUrl/icon/${it.pkg}.png",
                    repoUrl = repoUrl,
                )
            }
    }

    fun getApkUrl(extension: MangaExtension.Available): String {
        return extension.apkUrl
    }

    private fun ExtensionJsonObject.extractLibVersion(): Double {
        return version.substringBeforeLast('.').toDouble()
    }

    private fun ExtensionStoreExtensionDto.extractLibVersion(): Double {
        return extensionLib.toDoubleOrNull()
            ?: versionName.substringBeforeLast('.').toDoubleOrNull()
            ?: 0.0
    }
}

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<ExtensionSourceJsonObject>?,
)

@Serializable
private data class ExtensionSourceJsonObject(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

private val storeSourceMapper: (ExtensionStoreSourceDto) -> MangaExtension.Available.MangaSource = {
    MangaExtension.Available.MangaSource(
        id = it.id,
        lang = it.language,
        name = it.name,
        baseUrl = it.homeUrl,
    )
}

private val extensionSourceMapper: (ExtensionSourceJsonObject) -> MangaExtension.Available.MangaSource = {
    MangaExtension.Available.MangaSource(
        id = it.id,
        lang = it.lang,
        name = it.name,
        baseUrl = it.baseUrl,
    )
}
