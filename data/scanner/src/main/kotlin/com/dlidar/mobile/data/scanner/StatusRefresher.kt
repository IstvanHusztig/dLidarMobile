package com.dlidar.mobile.data.scanner

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Merges status refresh requests: at most one status.get in flight and one queued, however many
 * gaps arrive. A refresh that did not get through is repeated with [CallRetry]'s delays.
 * Requests made before [start] wait for it.
 */
internal class StatusRefresher(
    private val scope: CoroutineScope,
    /** Fetches the status once; returns false when it is worth trying again. */
    private val refresh: suspend () -> Boolean,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null

    fun request() {
        requests.trySend(Unit)
    }

    fun start() {
        if (worker != null) return
        worker = scope.launch {
            for (request in requests) {
                var attempt = 0
                while (!refresh()) delay(CallRetry.delayAfter(++attempt))
            }
        }
    }
}
