package com.dlidar.mobile

import androidx.compose.runtime.Composable
import com.dlidar.mobile.domain.model.ScannerCandidate

/** Release builds ship without the link test. */
object DebugTools {
    const val linkTestAvailable = false

    @Suppress("UNUSED_PARAMETER")
    @Composable
    fun LinkTest(container: AppContainer, candidate: ScannerCandidate, onBack: () -> Unit) = Unit
}
