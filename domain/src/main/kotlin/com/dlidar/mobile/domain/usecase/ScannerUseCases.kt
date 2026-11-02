package com.dlidar.mobile.domain.usecase

import com.dlidar.mobile.domain.model.Discovery
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

class DismissWarning(private val repository: ScannerRepository) {
    operator fun invoke(warningId: Long) = repository.dismissWarning(warningId)
}
