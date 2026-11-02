package com.dlidar.mobile.data.scanner

import com.dlidar.mobile.data.protocol.ProtocolLimits
import com.dlidar.mobile.data.protocol.codec.ErrorCode
import com.dlidar.mobile.data.protocol.session.CallFailure
import com.dlidar.mobile.data.protocol.session.CallResult
import com.dlidar.mobile.data.scanner.dto.HelloInfo
import com.dlidar.mobile.domain.model.Compatibility
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Reads the answer to hello. hello is optional, so anything but a clear mismatch is not fatal. */
internal object CompatibilityChecker {

    fun check(hello: CallResult<HelloInfo>): Compatibility = when (hello) {
        is CallResult.Success -> when (val protocol = hello.value.protocol) {
            null -> Compatibility.Unknown
            ProtocolLimits.PROTOCOL_VERSION -> Compatibility.Compatible
            else -> mismatch(protocol)
        }
        is CallResult.Failure -> {
            val failure = hello.failure
            if (failure is CallFailure.Remote && failure.error.code == ErrorCode.UnsupportedVersion) {
                // Spec: "supported" lists every version the scanner speaks.
                val supported = (failure.error.details["supported"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
                mismatch(supported?.maxOrNull())
            } else {
                // unknown_command (an older scanner), timeouts and the rest: carry on.
                Compatibility.Unknown
            }
        }
    }

    /**
     * Spec: an app above the scanner's newest version means "update the scanner"; otherwise
     * (below its oldest, or nothing known) "update the app".
     */
    private fun mismatch(serverMax: Int?): Compatibility =
        if (serverMax != null && ProtocolLimits.PROTOCOL_VERSION > serverMax) Compatibility.UpdateScannerRequired(serverMax)
        else Compatibility.UpdateAppRequired(serverMax)
}
