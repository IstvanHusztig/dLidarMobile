package com.dlidar.mobile.data.scanner.wifi

import com.dlidar.mobile.data.scanner.dto.WifiStatusDto
import com.dlidar.mobile.domain.model.LastJoin
import com.dlidar.mobile.domain.model.ScannerWifiStatus
import com.dlidar.mobile.domain.model.WifiJoinResult
import com.dlidar.mobile.domain.model.WifiState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Wi-Fi wire values to domain types. Unknown codes and states become the generic ones. */
internal object WifiMappers {

    /**
     * Reads a `result` event: `ok:true` with `{ssid, ip}`, or `ok:false` with an error. Throws
     * [IllegalArgumentException] when it is neither.
     */
    fun joinResult(event: JsonObject): WifiJoinResult {
        val ok = (event["ok"] as? JsonPrimitive)?.booleanOrNull ?: throw IllegalArgumentException("result event without ok")
        if (ok) {
            val result = event["result"] as? JsonObject ?: throw IllegalArgumentException("result event without result")
            val ssid = result.string("ssid") ?: throw IllegalArgumentException("result without ssid")
            return WifiJoinResult.Connected(ssid, result.string("ip"))
        }
        val error = event["error"] as? JsonObject ?: throw IllegalArgumentException("result event without error")
        val code = error.string("code") ?: throw IllegalArgumentException("error without code")
        return failure(code, error.string("message"))
    }

    fun status(dto: WifiStatusDto) = ScannerWifiStatus(
        state = state(dto.state),
        ssid = dto.ssid,
        ip = dto.ip,
        last = dto.last?.let { last ->
            LastJoin(
                ssid = last.ssid,
                result = when (last.result) {
                    "pending" -> null
                    // Spec: the ip of a connected join is the status's own, when it is that network.
                    "connected" -> WifiJoinResult.Connected(last.ssid, dto.ip.takeIf { dto.ssid == last.ssid })
                    else -> failure(last.result, message = null)
                },
            )
        },
    )

    private fun state(raw: String): WifiState = when (raw) {
        "connected" -> WifiState.Connected
        "connecting" -> WifiState.Connecting
        "disconnected" -> WifiState.Disconnected
        "unavailable" -> WifiState.Unavailable
        else -> WifiState.Unknown(raw)
    }

    private fun failure(code: String, message: String?): WifiJoinResult = when (code) {
        "wrong_password" -> WifiJoinResult.WrongPassword
        "not_found" -> WifiJoinResult.NotFound
        "timeout" -> WifiJoinResult.Timeout
        // failed, and codes this app does not know: generic, with the scanner's text.
        else -> WifiJoinResult.Failed(message ?: code)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
