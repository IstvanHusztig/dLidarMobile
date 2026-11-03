package com.dlidar.mobile.domain.usecase

import com.dlidar.mobile.domain.model.Discovery
import com.dlidar.mobile.domain.model.NetworkListMerger
import com.dlidar.mobile.domain.model.ScannerWifiStatus
import com.dlidar.mobile.domain.model.WifiCredentials
import com.dlidar.mobile.domain.model.WifiJoinResult
import com.dlidar.mobile.domain.model.WifiNetwork
import com.dlidar.mobile.domain.model.WifiPassword
import com.dlidar.mobile.domain.model.WifiScan
import com.dlidar.mobile.domain.port.ScannerWifi
import com.dlidar.mobile.domain.port.WifiNetworkScanner
import com.dlidar.mobile.domain.result.DomainError
import com.dlidar.mobile.domain.model.RecordingCommandResult
import com.dlidar.mobile.domain.model.ScannerCandidate
import com.dlidar.mobile.domain.model.ScannerSnapshot
import com.dlidar.mobile.domain.port.ScannerFinder
import com.dlidar.mobile.domain.port.ScannerRepository
import com.dlidar.mobile.domain.result.Outcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// One class per use case. They are thin today; rules that belong to a use case go here, not
// into the ViewModels or the repository.

class FindScanners(private val finder: ScannerFinder) {
    operator fun invoke(): Flow<Discovery> = finder.find()
}

class ConnectScanner(private val repository: ScannerRepository) {
    operator fun invoke(candidate: ScannerCandidate) = repository.connect(candidate)
}

class DisconnectScanner(private val repository: ScannerRepository) {
    operator fun invoke() = repository.disconnect()
}

class ObserveScanner(private val repository: ScannerRepository) {
    operator fun invoke(): StateFlow<ScannerSnapshot> = repository.snapshot
}

class RefreshStatus(private val repository: ScannerRepository) {
    suspend operator fun invoke(): Outcome<Unit> = repository.refreshStatus()
}

class StartRecording(private val repository: ScannerRepository) {
    suspend operator fun invoke(): Outcome<RecordingCommandResult> = repository.startRecording()
}

class StopRecording(private val repository: ScannerRepository) {
    suspend operator fun invoke(): Outcome<RecordingCommandResult> = repository.stopRecording()
}

/** The phone's networks, merged for display ([NetworkListMerger]). */
class ScanWifiNetworks(private val scanner: WifiNetworkScanner) {
    suspend operator fun invoke(): WifiScan = when (val scan = scanner.scan()) {
        is WifiScan.Networks -> scan.copy(networks = NetworkListMerger.merge(scan.networks))
        is WifiScan.Failed -> scan
    }
}

/** Joins a network after checking the scanner's rules, so a bad request is never sent. */
class JoinWifi(private val wifi: ScannerWifi) {
    suspend operator fun invoke(network: WifiNetwork, password: WifiPassword?): Outcome<WifiJoinResult> {
        val chars = password?.toCharArray()
        val problem = try {
            WifiCredentials.problem(network.ssid, network.security, chars)
        } finally {
            chars?.fill('\u0000')
        }
        if (problem != null) {
            password?.clear()
            return Outcome.Failed(DomainError.InvalidInput(problem.name))
        }
        return wifi.join(network.ssid, network.security, password.takeIf { network.security.needsPassword })
    }
}

class ReadScannerWifi(private val wifi: ScannerWifi) {
    suspend operator fun invoke(): Outcome<ScannerWifiStatus> = wifi.status()
}

class DismissWarning(private val repository: ScannerRepository) {
    operator fun invoke(warningId: Long) = repository.dismissWarning(warningId)
}
