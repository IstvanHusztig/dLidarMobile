package com.dlidar.mobile.data.scanner.command

import com.dlidar.mobile.data.protocol.command.ProtocolCommand
import com.dlidar.mobile.data.scanner.dto.ScannerJson
import com.dlidar.mobile.data.scanner.dto.WifiConnectAnswerDto
import com.dlidar.mobile.data.scanner.dto.WifiStatusDto
import com.dlidar.mobile.domain.model.WifiSecurity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `wifi.connect {ssid, security, psk}`. The answer only says the join started; its outcome
 * comes later in a `result` event. [psk] is redacted in every log. The JSON line itself has to
 * hold it as a string, which the session drops once written.
 */
internal class WifiConnectCommand(
    private val ssid: String,
    private val security: WifiSecurity,
    private val psk: CharArray?,
) : ProtocolCommand<WifiConnectAnswerDto> {
    override val name = "wifi.connect"
    override val sensitiveArgs = setOf("psk")

    override fun args(): JsonObject = buildJsonObject {
        put("ssid", ssid)
        put("security", wire(security))
        // Spec: no psk for an open network.
        psk?.let { put("psk", String(it)) }
    }

    override fun decode(result: JsonObject): WifiConnectAnswerDto =
        ScannerJson.decodeFromJsonElement(WifiConnectAnswerDto.serializer(), result)

    private fun wire(security: WifiSecurity): String = when (security) {
        WifiSecurity.Wpa2Psk -> "wpa2-psk"
        WifiSecurity.Wpa3Sae -> "wpa3-sae"
        WifiSecurity.Open -> "open"
        is WifiSecurity.Unsupported -> throw IllegalArgumentException("the scanner cannot join ${security.kind} networks")
    }
}

internal object WifiStatusCommand : NoArgsCommand<WifiStatusDto>("wifi.status", WifiStatusDto.serializer())

/** Commands a scanner must list in hello before the app offers Wi-Fi setup. */
internal val WIFI_COMMANDS = setOf("wifi.connect", WifiStatusCommand.name)
