package com.dlidar.mobile

import androidx.compose.runtime.Composable
import com.dlidar.mobile.data.protocol.session.TrafficListener
import com.dlidar.mobile.domain.model.ScannerCandidate

/** Release builds ship without the link test and the traffic log. */
object DebugTools {
    const val linkTestAvailable = false
    const val trafficLogAvailable = false

    val traffic: TrafficListener get() = TrafficListener.None

    @Suppress("UNUSED_PARAMETER")
    fun start(container: AppContainer) = Unit

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun LinkTest(container: AppContainer, candidate: ScannerCandidate, onBack: () -> Unit) = Unit

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun TrafficLog(onBack: () -> Unit) = Unit
}
