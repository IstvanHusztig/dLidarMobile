package com.dlidar.mobile.testing

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The recording status the fake scanner reports, in wire terms. */
data class FakeStatus(
    val state: String = "idle",
    val chunks: Long = 0,
    val points: Long = 0,
    val lastOk: Boolean = true,
    val location: String = "/media/usb/scans",
    val freeBytes: Long? = 64_000_000_000,
) {
    /** The result of status.get. */
    fun toResult(): JsonObject = buildJsonObject {
        putStateFields()
        put("free_bytes", freeBytes)
    }

    /** The data of a state event: the same fields without free_bytes. */
    fun toStateEvent(): JsonObject = buildJsonObject { putStateFields() }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putStateFields() {
        put("state", state)
        put("chunks", chunks)
        put("points", points)
        put("last_ok", lastOk)
        put("location", location)
    }
}
