package com.dlidar.mobile.infra.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Runtime permissions a Wi-Fi scan needs. `startScan` and `getScanResults` need fine location on
 * every version; NEARBY_WIFI_DEVICES does not replace it. Android 12 and newer let the user pick
 * approximate location, so coarse is asked for together with fine; only fine is enough.
 */
object WifiPermissions {

    val required: List<String>
        get() = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun granted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Scan results stay empty while location is switched off. */
    fun locationOffBlocksScan(context: Context): Boolean {
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return !LocationManagerCompat.isLocationEnabled(manager)
    }
}
