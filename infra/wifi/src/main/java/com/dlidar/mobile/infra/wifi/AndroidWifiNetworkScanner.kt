package com.dlidar.mobile.infra.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.dlidar.mobile.domain.model.WifiNetwork
import com.dlidar.mobile.domain.model.WifiScan
import com.dlidar.mobile.domain.port.WifiNetworkScanner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

/**
 * [WifiNetworkScanner] over `WifiManager`: starts a scan, waits for
 * `SCAN_RESULTS_AVAILABLE_ACTION`, and reads `scanResults`. When Android refuses or throttles
 * the scan, the cached results of an earlier one are returned, marked as cached.
 */
@SuppressLint("MissingPermission")
class AndroidWifiNetworkScanner internal constructor(
    private val context: Context,
    private val throttle: ScanThrottle,
) : WifiNetworkScanner {

    constructor(context: Context) : this(context.applicationContext, ScanThrottle(SystemClock::elapsedRealtime))

    override suspend fun scan(): WifiScan {
        if (!WifiPermissions.granted(context)) return WifiScan.Failed(WifiScan.Problem.PermissionMissing)
        val wifi = context.getSystemService(WifiManager::class.java) ?: return WifiScan.Failed(WifiScan.Problem.ScanFailed)
        if (!wifi.isWifiEnabled) return WifiScan.Failed(WifiScan.Problem.WifiOff)
        if (WifiPermissions.locationOffBlocksScan(context)) return WifiScan.Failed(WifiScan.Problem.LocationOff)
        val fresh = throttle.tryAcquire() && scanAndWait(wifi)
        return try {
            WifiScan.Networks(wifi.scanResults.map(::toNetwork), cached = !fresh)
        } catch (e: SecurityException) {
            WifiScan.Failed(WifiScan.Problem.PermissionMissing)
        }
    }

    /** Returns true when new results arrived. */
    private suspend fun scanAndWait(wifi: WifiManager): Boolean {
        val done = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                done.complete(intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false))
            }
        }
        // A protected system broadcast; exported so it does not depend on the sender's process.
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        return try {
            // Deprecated since Android 9 with no replacement; still the only way to ask for a scan.
            @Suppress("DEPRECATION")
            val started = wifi.startScan()
            started && withTimeoutOrNull(SCAN_TIMEOUT) { done.await() } == true
        } catch (e: SecurityException) {
            false
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    private fun toNetwork(result: ScanResult): WifiNetwork {
        // SSID is deprecated in API 33 for WifiSsid, whose text form is quoted; non-UTF-8 SSIDs
        // are out of scope, so the plain string is enough.
        @Suppress("DEPRECATION")
        val ssid = result.SSID?.takeUnless { it == UNKNOWN_SSID }.orEmpty()
        return WifiNetwork(ssid, SecurityTypeResolver.resolve(result.capabilities.orEmpty()), result.level)
    }

    private companion object {
        val SCAN_TIMEOUT = 15.seconds
        const val UNKNOWN_SSID = "<unknown ssid>"
    }
}
