package eu.kanade.tachiyomi.data.library

import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.Constraints
import androidx.work.NetworkType
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_CHARGING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_NETWORK_NOT_METERED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI

internal fun libraryUpdateConstraints(restrictions: Set<String>): Constraints {
    val networkType = if (DEVICE_NETWORK_NOT_METERED in restrictions) NetworkType.UNMETERED else NetworkType.CONNECTED
    return Constraints.Builder().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val network = NetworkRequest.Builder().apply {
                if (DEVICE_ONLY_ON_WIFI in restrictions) addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                if (DEVICE_NETWORK_NOT_METERED in restrictions) {
                    addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                }
            }.build()
            setRequiredNetworkRequest(network, networkType)
        } else {
            setRequiredNetworkType(networkType)
        }
        setRequiresCharging(DEVICE_CHARGING in restrictions)
        setRequiresBatteryNotLow(true)
    }.build()
}
