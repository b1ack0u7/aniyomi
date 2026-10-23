package eu.kanade.tachiyomi.extension.anime

import android.content.Context
import android.graphics.drawable.Drawable
import eu.kanade.domain.extension.anime.interactor.TrustAnimeExtension
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.extension.ExtensionUpdateNotifier
import eu.kanade.tachiyomi.extension.ExtensionUpdatePolicy
import eu.kanade.tachiyomi.extension.InstallStep
import eu.kanade.tachiyomi.extension.anime.api.AnimeExtensionApi
import eu.kanade.tachiyomi.extension.anime.model.AnimeExtension
import eu.kanade.tachiyomi.extension.anime.model.AnimeLoadResult
import eu.kanade.tachiyomi.extension.anime.util.AnimeExtensionInstallReceiver
import eu.kanade.tachiyomi.extension.anime.util.AnimeExtensionInstaller
import eu.kanade.tachiyomi.extension.anime.util.AnimeExtensionLoader
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.anime.model.StubAnimeSource
import tachiyomi.i18n.MR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The manager of anime extensions installed as another apk which extend the available sources. It handles
 * the retrieval of remotely available anime extensions as well as installing, updating and removing them.
 * To avoid malicious distribution, every anime extension must be signed and it will only be loaded if its
 * signature is trusted, otherwise the user will be prompted with a warning to trust it before being
 * loaded.
 *
 * @param context The application context.
 * @param preferences The application preferences.
 */
class AnimeExtensionManager(
    private val context: Context,
    private val preferences: SourcePreferences = Injekt.get(),
    private val trustExtension: TrustAnimeExtension = Injekt.get(),
) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    /**
     * API where all the available anime extensions can be found.
     */
    private val api = AnimeExtensionApi()

    /**
     * The installer which installs, updates and uninstalls the anime extensions.
     */
    private val installer by lazy { AnimeExtensionInstaller(context) }

    private val iconMap = ConcurrentHashMap<String, Drawable>()

    private val installedExtensionsMapFlow = MutableStateFlow(emptyMap<String, AnimeExtension.Installed>())
    val installedExtensionsFlow = installedExtensionsMapFlow.mapExtensions(scope)

    private val availableExtensionsMapFlow = MutableStateFlow(emptyMap<String, AnimeExtension.Available>())
    val availableExtensionsFlow = availableExtensionsMapFlow.mapExtensions(scope)

    private val untrustedExtensionsMapFlow = MutableStateFlow(emptyMap<String, AnimeExtension.Untrusted>())
    val untrustedExtensionsFlow = untrustedExtensionsMapFlow.mapExtensions(scope)

    private val loadMutex = Mutex()
    private var hasFetchedAvailableExtensions = false
    private var availableCandidates: List<AnimeExtension.Available> = emptyList()

    init {
        AnimeExtensionInstallReceiver(AnimeInstallationListener()).register(context)
        scope.launch {
            merge(
                flowOf(Unit),
                trustExtension.changes(),
                preferences.showNsfwSource().changes().distinctUntilChanged().map {},
            ).collect { loadExtensions() }
        }
    }

    suspend fun awaitInitialized() {
        isInitialized.first { it }
        installedExtensionsFlow.first { it == installedExtensionsMapFlow.value.values.toList() }
    }

    suspend fun getInstalledExtensions(): List<AnimeExtension.Installed> {
        awaitInitialized()
        return installedExtensionsMapFlow.value.values.toList()
    }

    private var subLanguagesEnabledOnFirstRun = preferences.enabledLanguages().isSet()

    fun getExtensionPackage(sourceId: Long): String? {
        return installedExtensionsFlow.value.find { extension ->
            extension.sources.any { it.id == sourceId }
        }
            ?.pkgName
    }

    fun getExtensionPackageAsFlow(sourceId: Long): Flow<String?> {
        return installedExtensionsFlow.map { extensions ->
            extensions.find { extension ->
                extension.sources.any { it.id == sourceId }
            }
                ?.pkgName
        }
    }

    fun getAppIconForSource(sourceId: Long): Drawable? {
        val pkgName = installedExtensionsMapFlow.value.values
            .find { ext ->
                ext.sources.any { it.id == sourceId }
            }
            ?.pkgName
            ?: return null

        return iconMap[pkgName] ?: iconMap.getOrPut(pkgName) {
            AnimeExtensionLoader.getAnimeExtensionPackageInfoFromPkgName(context, pkgName)!!.applicationInfo!!
                .loadIcon(context.packageManager)
        }
    }

    private var availableAnimeExtensionsSourcesData: Map<Long, StubAnimeSource> = emptyMap()

    private fun setupAvailableAnimeExtensionsSourcesDataMap(extensions: List<AnimeExtension.Available>) {
        if (extensions.isEmpty()) return
        availableAnimeExtensionsSourcesData = extensions
            .flatMap { ext -> ext.sources.map { it.toStubSource() } }
            .associateBy { it.id }
    }

    fun getSourceData(id: Long) = availableAnimeExtensionsSourcesData[id]

    /**
     * Loads and registers the installed animeextensions.
     */
    private suspend fun loadExtensions() = loadMutex.withLock {
        try {
            val extensions = AnimeExtensionLoader.loadExtensions(context, installedExtensionsMapFlow.value)
            installedExtensionsMapFlow.value = extensions
                .filterIsInstance<AnimeLoadResult.Success>()
                .associate { it.extension.pkgName to it.extension }
            untrustedExtensionsMapFlow.value = extensions
                .filterIsInstance<AnimeLoadResult.Untrusted>()
                .associate { it.extension.pkgName to it.extension }
            updateAvailableExtensions(availableCandidates)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to load anime extensions" }
        } finally {
            _isInitialized.value = true
        }
    }

    private fun updateAvailableExtensions(extensions: List<AnimeExtension.Available>) {
        availableCandidates = extensions
        if (!hasFetchedAvailableExtensions) return
        availableExtensionsMapFlow.value = extensions.groupBy { it.pkgName }.mapNotNull { (pkg, candidates) ->
            val installed = installedExtensionsMapFlow.value[pkg]
            ExtensionUpdatePolicy.select(
                candidates,
                installed?.signatureHashes,
                AnimeExtension.Available::repoSigningKey,
                AnimeExtension.Available::versionCode,
                AnimeExtension.Available::libVersion,
            )
                ?.let { pkg to it }
        }.toMap()
        updatedInstalledAnimeExtensionsStatuses(availableExtensionsMapFlow.value.values.toList())
    }

    /**
     * Finds the available anime extensions in the [api] and updates [availableExtensionsMapFlow].
     */
    suspend fun findAvailableExtensions() {
        val extensions: List<AnimeExtension.Available> = try {
            api.findExtensions()
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e)
            withUIContext { context.toast(MR.strings.extension_api_error) }
            emptyList()
        }

        enableAdditionalSubLanguages(extensions)

        awaitInitialized()
        loadMutex.withLock {
            hasFetchedAvailableExtensions = true
            updateAvailableExtensions(extensions)
        }
        setupAvailableAnimeExtensionsSourcesDataMap(extensions)
    }

    /**
     * Enables the additional sub-languages in the app first run. This addresses
     * the issue where users still need to enable some specific languages even when
     * the device language is inside that major group. As an example, if a user
     * has a zh device language, the app will also enable zh-Hans and zh-Hant.
     *
     * If the user have already changed the enabledLanguages preference value once,
     * the new languages will not be added to respect the user enabled choices.
     */
    private fun enableAdditionalSubLanguages(extensions: List<AnimeExtension.Available>) {
        if (subLanguagesEnabledOnFirstRun || extensions.isEmpty()) {
            return
        }

        // Use the source lang as some aren't present on the extension level.
        val availableLanguages = extensions
            .flatMap(AnimeExtension.Available::sources)
            .distinctBy(AnimeExtension.Available.AnimeSource::lang)
            .map(AnimeExtension.Available.AnimeSource::lang)

        val deviceLanguage = Locale.getDefault().language
        val defaultLanguages = preferences.enabledLanguages().defaultValue()
        val languagesToEnable = availableLanguages.filter {
            it != deviceLanguage && it.startsWith(deviceLanguage)
        }

        preferences.enabledLanguages().set(defaultLanguages + languagesToEnable)
        subLanguagesEnabledOnFirstRun = true
    }

    /**
     * Sets the update field of the installed animeextensions with the given [availableExtensions].
     *
     * @param availableExtensions The list of animeextensions given by the [api].
     */
    private fun updatedInstalledAnimeExtensionsStatuses(
        availableExtensions: List<AnimeExtension.Available>,
    ) {
        val installedExtensionsMap = installedExtensionsMapFlow.value.toMutableMap()
        var changed = false

        for ((pkgName, extension) in installedExtensionsMap) {
            val availableExt = availableExtensions.find { it.pkgName == pkgName }

            if (availableExt == null && !extension.isObsolete) {
                installedExtensionsMap[pkgName] = extension.copy(isObsolete = true, hasUpdate = false, repoUrl = null)
                changed = true
            } else if (availableExt != null) {
                val hasUpdate = extension.updateExists(availableExt)
                if (extension.hasUpdate != hasUpdate) {
                    installedExtensionsMap[pkgName] = extension.copy(
                        hasUpdate = hasUpdate,
                        isObsolete = false,
                        repoUrl = availableExt.repoUrl,
                    )
                } else {
                    installedExtensionsMap[pkgName] = extension.copy(
                        isObsolete = false,
                        repoUrl = availableExt.repoUrl,
                    )
                }
                changed = true
            }
        }
        if (changed) {
            installedExtensionsMapFlow.value = installedExtensionsMap
        }
        updatePendingUpdatesCount()
    }

    /**
     * Returns a flow of the installation process for the given anime extension. It will complete
     * once the anime extension is installed or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The anime extension to be installed.
     */
    fun installExtension(extension: AnimeExtension.Available): Flow<InstallStep> {
        val requestedExtension = extension.copy(pkgName = extension.pkgName.substringBefore('-'))
        return installer.downloadAndInstall(api.getApkUrl(requestedExtension), requestedExtension)
    }

    /**
     * Returns a flow of the installation process for the given anime extension. It will complete
     * once the anime extension is updated or throws an error. The process will be canceled if
     * unsubscribed before its completion.
     *
     * @param extension The anime extension to be updated.
     */
    fun updateExtension(extension: AnimeExtension.Installed): Flow<InstallStep> {
        val availableExt = availableExtensionsMapFlow.value[extension.pkgName] ?: return emptyFlow()
        return installExtension(availableExt)
    }

    fun cancelInstallUpdateExtension(extension: AnimeExtension) {
        installer.cancelInstall(extension.pkgName.substringBefore('-'))
    }

    /**
     * Sets to "installing" status of an anime extension installation.
     *
     * @param downloadId The id of the download.
     */
    fun setInstalling(downloadId: Long) {
        installer.updateInstallStep(downloadId, InstallStep.Installing)
    }

    fun updateInstallStep(downloadId: Long, step: InstallStep) {
        installer.updateInstallStep(downloadId, step)
    }

    /**
     * Uninstalls the anime extension that matches the given package name.
     *
     * @param extension The extension to uninstall.
     */
    fun uninstallExtension(extension: AnimeExtension) {
        installer.uninstallApk(extension.pkgName)
    }

    /**
     * Adds the given extension to the list of trusted extensions. It also loads in background the
     * now trusted extensions.
     *
     * @param extension the extension to trust
     */
    suspend fun trust(extension: AnimeExtension.Untrusted) {
        untrustedExtensionsMapFlow.value[extension.pkgName] ?: return
        trustExtension.trust(extension.pkgName, extension.versionCode, extension.signatureHash)
    }

    /**
     * Registers the given anime extension in this and the source managers.
     *
     * @param extension The anime extension to be registered.
     */
    private fun registerNewExtension(extension: AnimeExtension.Installed) {
        untrustedExtensionsMapFlow.value -= extension.pkgName
        iconMap.remove(extension.pkgName)
        installedExtensionsMapFlow.value += extension
    }

    /**
     * Registers the given updated anime extension in this and the source managers previously removing
     * the outdated ones.
     *
     * @param extension The anime extension to be registered.
     */
    private fun registerUpdatedExtension(extension: AnimeExtension.Installed) {
        untrustedExtensionsMapFlow.value -= extension.pkgName
        iconMap.remove(extension.pkgName)
        installedExtensionsMapFlow.value += extension
    }

    /**
     * Unregisters the animeextension in this and the source managers given its package name. Note this
     * method is called for every uninstalled application in the system.
     *
     * @param pkgName The package name of the uninstalled application.
     */
    private fun unregisterExtension(pkgName: String) {
        installedExtensionsMapFlow.value -= pkgName
        untrustedExtensionsMapFlow.value -= pkgName
    }

    /**
     * Listener which receives events of the anime extensions being installed, updated or removed.
     */
    private inner class AnimeInstallationListener : AnimeExtensionInstallReceiver.Listener {

        override fun onExtensionInstalled(extension: AnimeExtension.Installed) {
            scope.launch {
                loadMutex.withLock {
                    registerNewExtension(extension.withUpdateCheck())
                    updatePendingUpdatesCount()

                    updateAvailableExtensions(availableCandidates)
                }
            }
        }

        override fun onExtensionUpdated(extension: AnimeExtension.Installed) {
            scope.launch {
                loadMutex.withLock {
                    registerUpdatedExtension(extension.withUpdateCheck())
                    updatePendingUpdatesCount()

                    updateAvailableExtensions(availableCandidates)
                }
            }
        }

        override fun onExtensionUntrusted(extension: AnimeExtension.Untrusted) {
            scope.launch {
                loadMutex.withLock {
                    installedExtensionsMapFlow.value -= extension.pkgName
                    untrustedExtensionsMapFlow.value += extension
                    updatePendingUpdatesCount()

                    updateAvailableExtensions(availableCandidates)
                }
            }
        }

        override fun onPackageUninstalled(pkgName: String) {
            scope.launch {
                loadMutex.withLock {
                    AnimeExtensionLoader.uninstallPrivateExtension(context, pkgName)
                    unregisterExtension(pkgName)
                    updatePendingUpdatesCount()

                    updateAvailableExtensions(availableCandidates)
                }
            }
        }
    }

    /**
     * AnimeExtension method to set the update field of an installed anime extension.
     */
    private fun AnimeExtension.Installed.withUpdateCheck(): AnimeExtension.Installed {
        return if (updateExists()) {
            copy(hasUpdate = true)
        } else {
            this
        }
    }

    private fun AnimeExtension.Installed.updateExists(
        availableExtension: AnimeExtension.Available? = null,
    ): Boolean {
        val availableExt = availableExtension
            ?: availableExtensionsMapFlow.value[pkgName]
            ?: return false

        return (availableExt.versionCode > versionCode || availableExt.libVersion > libVersion)
    }

    private fun updatePendingUpdatesCount() {
        val pendingUpdateCount = installedExtensionsMapFlow.value.values.count { it.hasUpdate }
        preferences.animeExtensionUpdatesCount().set(pendingUpdateCount)
        if (pendingUpdateCount == 0) {
            ExtensionUpdateNotifier(context).dismiss()
        }
    }

    private operator fun <T : AnimeExtension> Map<String, T>.plus(extension: T) = plus(extension.pkgName to extension)

    private fun <T : AnimeExtension> StateFlow<Map<String, T>>.mapExtensions(
        scope: CoroutineScope,
    ): StateFlow<List<T>> {
        return map { it.values.toList() }.stateIn(scope, SharingStarted.Eagerly, value.values.toList())
    }
}
