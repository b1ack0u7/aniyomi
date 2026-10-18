package eu.kanade.tachiyomi.network

import java.io.IOException

class CloudflareChallengeException(message: String, cause: Throwable? = null) : IOException(message, cause)
