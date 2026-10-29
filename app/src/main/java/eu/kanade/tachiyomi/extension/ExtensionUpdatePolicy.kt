package eu.kanade.tachiyomi.extension

internal object ExtensionUpdatePolicy {
    fun <T> select(
        candidates: List<T>,
        installedSignatures: Set<String>?,
        signingKey: (T) -> String,
        versionCode: (T) -> Long,
        libVersion: (T) -> Double,
    ): T? = candidates
        .filter { installedSignatures == null || signingKey(it) in installedSignatures }
        .maxWithOrNull(compareBy(versionCode).thenBy(libVersion))

    fun matchesDownloadedApk(
        actualPackageName: String,
        actualVersionCode: Long,
        actualSignatures: Set<String>,
        expectedPackageName: String,
        expectedVersionCode: Long,
        repositorySigningKey: String,
    ): Boolean = repositorySigningKey.isNotBlank() &&
        actualPackageName == expectedPackageName &&
        actualVersionCode == expectedVersionCode &&
        repositorySigningKey in actualSignatures
}
