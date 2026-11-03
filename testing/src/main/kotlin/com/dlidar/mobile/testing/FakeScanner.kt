package com.dlidar.mobile.testing

import com.dlidar.mobile.data.protocol.framing.DecodedFrame
import com.dlidar.mobile.data.protocol.framing.FrameDecoder
import com.dlidar.mobile.data.protocol.transport.ByteConnection
import com.dlidar.mobile.data.protocol.transport.ByteTransport
import com.dlidar.mobile.data.protocol.transport.ConnectResult
import com.dlidar.mobile.data.protocol.transport.DownReason
import com.dlidar.mobile.data.protocol.transport.LinkState
import com.dlidar.mobile.data.protocol.transport.WriteResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Plays the scanner's side of the link, following protocol v1: it reassembles written pieces,
 * answers hello, status.get (with the recording's warnings and the last summary), info.get,
 * recording.start, recording.stop, wifi.connect and wifi.status (only when hello lists them,
 * as an older scanner answers unknown_command), numbers events from 1 per connection, and splits
 * everything it sends into notifications of at most MTU-3 bytes, each message starting in a
 * fresh notification.
 *
 * It also checks the app: pieces larger than MTU-3, requests without `"v":1`, and undocumented
 * args are recorded in [violations] (and args are rejected with bad_request, as the scanner does).
 */
class FakeScanner(var mtu: Int = 247) : ByteTransport {

    private val linkState = MutableStateFlow<LinkState>(LinkState.Idle)
    override val link: StateFlow<LinkState> = linkState.asStateFlow()

    /** Every request received, in order, across connections. */
    val requests: MutableList<JsonObject> = CopyOnWriteArrayList()

    /** Protocol rules the app broke. Tests should assert this stays empty. */
    val violations: MutableList<String> = CopyOnWriteArrayList()

    /** Outcomes for the next connect attempts; when empty, connecting succeeds. */
    val connectFailures: ArrayDeque<DownReason> = ArrayDeque()

    var connectAttempts = 0
        private set

    var status = FakeStatus()

    /** The hello result; null makes hello answer unknown_command. */
    var hello: JsonObject? = defaultHello()

    var info: JsonObject = buildJsonObject {
        put("version", "1.0.0")
        put("sensor", "unitree_l2")
        put("sensor_model", "L2")
        put("recording_format", 3)
        put("protocol", 1)
    }

    /** Commands the fake receives but never answers. */
    val silentCommands: MutableSet<String> = mutableSetOf()

    /** Error codes to answer for a command before it answers normally again, one per request. */
    val scriptedErrors: MutableMap<String, ArrayDeque<String>> = mutableMapOf()

    /** False turns the fake into a plain byte peer that only records [writtenPieces]. */
    var protocolEnabled = true

    /** True answers every line like the scanner's echo test service: `echo <line>`. */
    var echoMode = false

    /**
     * True: an accepted start or stop runs through the states at once (preparing → recording,
     * finalizing → idle). False leaves it in preparing or finalizing for the test to move on.
     */
    var recordingAdvances = true

    // --- Wi-Fi ---------------------------------------------------------------------------------

    /** The outcome of the next joins: "connected" or a result error code (wrong_password, ...). */
    var wifiJoinOutcome = "connected"

    /** True: a join ends at once. False: it runs until [finishWifiJoin], as on the Pi. */
    var wifiJoinFinishes = true

    var wifiTimeoutS = 60
    var wifiIp: String? = "192.168.1.50"
    /** "unavailable" makes every join fail as the Pi does without a Wi-Fi device. */
    var wifiDevice = "present"

    var wifiState = "disconnected"
        private set
    var wifiSsid: String? = null
        private set
    /** The networks joined and saved, in order; the same SSID again replaces its entry. */
    val savedNetworks: MutableList<String> = mutableListOf()
    private var lastJoin: Pair<String, String>? = null
    private var runningJoin: RunningJoin? = null

    private class RunningJoin(val op: JsonElement, val ssid: String, val connection: Any?, val before: Pair<String, String?>)

    /** Every piece written, in order, across connections. */
    val writtenPieces: MutableList<ByteArray> = CopyOnWriteArrayList()

    private var connection: FakeConnection? = null
    private var seq = 0L
    /** The current or last recording's warnings, as status.get reports them. */
    private val recentWarnings = ArrayDeque<JsonObject>()
    private var lastSummary: JsonObject? = null

    val commandNames: List<String> get() = requests.map { it["cmd"]!!.jsonPrimitive.content }

    override suspend fun connect(target: String): ConnectResult {
        connectAttempts++
        connection?.end()
        linkState.value = LinkState.Connecting
        connectFailures.removeFirstOrNull()?.let { reason ->
            linkState.value = LinkState.Down(reason)
            return ConnectResult.Failed(reason)
        }
        val fresh = FakeConnection(maxWritePiece = mtu - 3)
        connection = fresh
        seq = 0
        linkState.value = LinkState.Up(fresh)
        return ConnectResult.Connected(fresh)
    }

    override suspend fun disconnect() {
        if (endConnection() || linkState.value !is LinkState.Idle) linkState.value = LinkState.Down(DownReason.Closed)
    }

    /** The link drops, as when the scanner goes out of range. */
    fun dropLink(status: Int = 8) {
        endConnection()
        linkState.value = LinkState.Down(DownReason.ConnectionLost(status))
    }

    val isConnected: Boolean get() = connection != null

    // --- Scanner behaviour -------------------------------------------------------------------

    /** Changes the recording status and announces it with a state event. */
    fun changeStatus(newStatus: FakeStatus) {
        // Spec: the warning list is emptied when a recording starts.
        if (newStatus.state == "preparing" && status.state != "preparing") recentWarnings.clear()
        status = newStatus
        emitEvent("state", newStatus.toStateEvent())
    }

    fun progress(chunks: Long, points: Long) {
        status = status.copy(chunks = chunks, points = points)
        emitEvent("progress", buildJsonObject {
            put("chunks", chunks)
            put("points", points)
        })
    }

    fun warning(kind: String, text: String) {
        val warning = buildJsonObject {
            put("kind", kind)
            put("text", text)
        }
        recentWarnings += warning
        while (recentWarnings.size > MAX_STATUS_WARNINGS) recentWarnings.removeFirst()
        emitEvent("warning", warning)
    }

    fun summary(imuMode: String = "fused", decision: String = "keep", imuIn: Long = 1000, imuOut: Long = 998, lostSamples: Long = 2) {
        val summary = buildJsonObject {
            put("imu_mode", imuMode)
            put("decision", decision)
            put("imu_in", imuIn)
            put("imu_out", imuOut)
            put("lost_samples", lostSamples)
        }
        lastSummary = summary
        emitEvent("summary", summary)
    }

    /**
     * Sends an event. [skip] leaves out that many seq numbers (a gap); [dropped] adds the field.
     * [extra] adds unknown top-level fields.
     */
    fun emitEvent(name: String, data: JsonObject, skip: Int = 0, dropped: Long? = null, extra: Map<String, JsonElement> = emptyMap()) {
        // Spec: with no phone connected nothing is sent or counted.
        if (connection == null) return
        seq += 1 + skip
        sendJson(buildJsonObject {
            put("event", name)
            put("seq", seq)
            if (dropped != null) put("dropped", dropped)
            put("data", data)
            extra.forEach { (key, value) -> put(key, value) }
        })
    }

    fun sendUnsolicitedError(code: String, message: String = code) {
        sendJson(buildJsonObject {
            put("id", JsonNull)
            put("ok", false)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        })
    }

    /** Sends one raw line, split into notifications of at most MTU-3 bytes. */
    fun sendLine(line: String) {
        val current = connection ?: error("not connected")
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        for (start in bytes.indices step current.maxWritePiece) {
            current.notify(bytes.copyOfRange(start, minOf(start + current.maxWritePiece, bytes.size)))
        }
    }

    /** Sends one notification exactly as given. */
    fun notifyRaw(bytes: ByteArray) {
        (connection ?: error("not connected")).notify(bytes)
    }

    private fun sendJson(message: JsonObject) = sendLine(Json.encodeToString(JsonObject.serializer(), message))

    private fun endConnection(): Boolean {
        val current = connection ?: return false
        connection = null
        current.end()
        return true
    }

    // --- Requests ----------------------------------------------------------------------------

    private fun onRequestLine(line: String) {
        val request = try {
            Json.parseToJsonElement(line).jsonObject
        } catch (e: IllegalArgumentException) {
            violations += "request is not a JSON object: $line"
            return sendUnsolicitedError("bad_request")
        }
        requests += request
        val id = request["id"] ?: JsonNull
        val cmd = (request["cmd"] as? JsonPrimitive)?.content
        if ((request["v"] as? JsonPrimitive)?.content != "1") violations += "request without \"v\":1: $line"
        if (cmd == null) return respondError(id, "bad_request", "missing cmd")
        if (cmd in silentCommands) return
        val args = request["args"]
        val documented = DOCUMENTED_ARGS[cmd].orEmpty()
        if (args != null && (args !is JsonObject || !documented.containsAll(args.keys))) {
            violations += "undocumented args for $cmd: ${redactPsk(line)}"
            return respondError(id, "bad_request", "unknown arg")
        }
        scriptedErrors[cmd]?.removeFirstOrNull()?.let { code -> return respondError(id, code, code) }
        when (cmd) {
            "hello" -> hello?.let { respond(id, it) } ?: respondError(id, "unknown_command", "unknown command")
            "status.get" -> respond(id, statusResult())
            "info.get" -> respond(id, info)
            "wifi.connect" -> if (lists(cmd)) wifiConnect(id, request["args"] as? JsonObject) else respondError(id, "unknown_command", "unknown command")
            "wifi.status" -> if (lists(cmd)) respond(id, wifiStatus()) else respondError(id, "unknown_command", "unknown command")
            "recording.start" -> if (lists(cmd)) recordingStart(id) else respondError(id, "unknown_command", "unknown command")
            "recording.stop" -> if (lists(cmd)) recordingStop(id) else respondError(id, "unknown_command", "unknown command")
            else -> respondError(id, "unknown_command", "unknown command")
        }
    }

    /** Spec: wifi.connect checks its arguments, answers at once, and reports the outcome later. */
    private fun wifiConnect(id: JsonElement, args: JsonObject?) {
        val ssid = args?.text("ssid")
        val security = args?.text("security")
        val psk = args?.get("psk")
        val badRequest = when {
            ssid == null -> "wifi.connect needs \"ssid\""
            ssid.toByteArray(Charsets.UTF_8).size !in 1..32 -> "\"ssid\" must be 1 to 32 bytes"
            security == null -> "wifi.connect needs \"security\""
            security !in listOf("wpa2-psk", "wpa3-sae", "open") -> "unknown \"security\": use wpa2-psk, wpa3-sae or open"
            security == "open" && psk != null -> "\"psk\" must be left out for an open network"
            security != "open" && psk == null -> "\"psk\" is needed for a network with a password"
            psk != null && (psk !is JsonPrimitive || !psk.isString) -> "\"psk\" must be a string"
            psk != null && !validPsk((psk as JsonPrimitive).content) ->
                "\"psk\" must be 8 to 63 printable ASCII characters or 64 hexadecimal digits"
            else -> null
        }
        if (badRequest != null) {
            violations += "bad wifi.connect: $badRequest"
            return respondError(id, "bad_request", badRequest)
        }
        if (runningJoin != null) return respondError(id, "busy", "another Wi-Fi join is still running; try again when it has ended")
        runningJoin = RunningJoin(id, ssid!!, connection, wifiState to wifiSsid)
        wifiState = "connecting"
        wifiSsid = ssid
        lastJoin = ssid to "pending"
        respond(id, buildJsonObject {
            put("accepted", true)
            put("timeout_s", wifiTimeoutS)
        })
        if (wifiJoinFinishes) finishWifiJoin()
    }

    /**
     * Ends the running join with [outcome]. The result event goes only to the connection that
     * asked, and only while it is still up: after a drop it is lost, as on the Pi.
     */
    fun finishWifiJoin(outcome: String = wifiJoinOutcome) {
        val join = runningJoin ?: error("no Wi-Fi join is running")
        runningJoin = null
        val result = if (wifiDevice == "unavailable") "failed" else outcome
        if (result == "connected") {
            wifiState = "connected"
            wifiSsid = join.ssid
            savedNetworks.remove(join.ssid)
            savedNetworks += join.ssid
        } else {
            // Spec: nothing is saved, and the Pi goes back to the network it was on.
            wifiState = join.before.first
            wifiSsid = join.before.second
        }
        lastJoin = join.ssid to result
        if (connection == null || connection !== join.connection) return
        seq += 1
        sendJson(buildJsonObject {
            put("event", "result")
            put("seq", seq)
            put("op", join.op)
            if (result == "connected") {
                put("ok", true)
                put("result", buildJsonObject {
                    put("ssid", join.ssid)
                    put("ip", wifiIp)
                })
            } else {
                put("ok", false)
                put("error", buildJsonObject {
                    put("code", result)
                    put("message", if (result == "failed") "dLidar may not change the network: install systemd/50-dlidar-network.rules" else result)
                })
            }
        })
    }

    private fun wifiStatus() = buildJsonObject {
        put("state", if (wifiDevice == "unavailable") "unavailable" else wifiState)
        put("ssid", wifiSsid)
        put("ip", if (wifiState == "connected") wifiIp else null)
        val last = lastJoin
        if (last == null) {
            put("last", JsonNull)
        } else {
            put("last", buildJsonObject {
                put("ssid", last.first)
                put("result", last.second)
            })
        }
    }

    private fun validPsk(psk: String): Boolean =
        (psk.length in 8..63 && psk.all { it in ' '..'~' }) ||
            (psk.length == 64 && psk.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' })

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun redactPsk(line: String) = line.replace(Regex("\"psk\":\"[^\"]*\""), "\"psk\":\"***\"")

    private fun lists(cmd: String): Boolean =
        (hello?.get("commands") as? JsonArray)?.any { (it as? JsonPrimitive)?.content == cmd } == true

    /** Spec: the answer carries the state the command saw; the state events follow. */
    private fun recordingStart(id: JsonElement) {
        val seen = status.state
        when (seen) {
            "finalizing" -> respondError(id, "busy", "the last recording is still being saved; try again when it is idle")
            "idle", "error" -> {
                respond(id, recordingAnswer(accepted = true, seen))
                changeStatus(status.copy(state = "preparing", chunks = 0, points = 0))
                if (recordingAdvances) changeStatus(status.copy(state = "recording"))
            }
            else -> respond(id, recordingAnswer(accepted = false, seen))
        }
    }

    /** Spec: from preparing the start-up is abandoned (no files); from recording it is saved. */
    private fun recordingStop(id: JsonElement) {
        val seen = status.state
        when (seen) {
            "preparing" -> {
                respond(id, recordingAnswer(accepted = true, seen))
                changeStatus(status.copy(state = "idle"))
            }
            "recording" -> {
                respond(id, recordingAnswer(accepted = true, seen))
                changeStatus(status.copy(state = "finalizing"))
                if (recordingAdvances) changeStatus(status.copy(state = "idle", lastOk = true))
            }
            else -> respond(id, recordingAnswer(accepted = false, seen))
        }
    }

    private fun recordingAnswer(accepted: Boolean, seen: String) = buildJsonObject {
        put("accepted", accepted)
        put("state", seen)
    }

    private fun statusResult() = JsonObject(
        status.toResult() + mapOf(
            "warnings" to JsonArray(recentWarnings.toList()),
            "summary" to (lastSummary ?: JsonNull),
        ),
    )

    private fun respond(id: JsonElement, result: JsonObject) = sendJson(buildJsonObject {
        put("id", id)
        put("ok", true)
        put("result", result)
    })

    private fun respondError(id: JsonElement, code: String, message: String) = sendJson(buildJsonObject {
        put("id", id)
        put("ok", false)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    })

    private inner class FakeConnection(override val maxWritePiece: Int) : ByteConnection {
        private val channel = Channel<ByteArray>(Channel.UNLIMITED)
        /** Spec: a request is at most 4096 bytes including its newline. */
        private val decoder = FrameDecoder(maxMessageBytes = 4095)
        override val incoming: Flow<ByteArray> = channel.receiveAsFlow()

        override suspend fun write(piece: ByteArray): WriteResult {
            if (connection !== this) return WriteResult.Failed("not connected")
            writtenPieces += piece.copyOf()
            if (piece.size > maxWritePiece) violations += "piece of ${piece.size} bytes, limit $maxWritePiece"
            if (!protocolEnabled) return WriteResult.Ok
            if (echoMode) {
                decoder.feed(piece).filterIsInstance<DecodedFrame.Line>().forEach { line ->
                    runCatching { Json.parseToJsonElement(line.text).jsonObject }.getOrNull()?.let { requests += it }
                    sendLine("echo ${line.text}")
                }
                return WriteResult.Ok
            }
            for (frame in decoder.feed(piece)) {
                when (frame) {
                    is DecodedFrame.Line -> onRequestLine(frame.text)
                    else -> {
                        violations += "unreadable request: $frame"
                        sendUnsolicitedError("too_large")
                    }
                }
            }
            return WriteResult.Ok
        }

        fun notify(piece: ByteArray) {
            channel.trySend(piece)
        }

        fun end() {
            channel.close()
        }
    }

    companion object {
        /** Spec: status.get lists the 16 newest warnings. */
        const val MAX_STATUS_WARNINGS = 16

        val CURRENT_COMMANDS = listOf("hello", "status.get", "info.get", "recording.start", "recording.stop", "wifi.connect", "wifi.status")

        /** The commands of a scanner from before Wi-Fi setup. */
        val RECORDING_COMMANDS = listOf("hello", "status.get", "info.get", "recording.start", "recording.stop")

        /** Spec: the only documented arguments; anything else is bad_request. */
        private val DOCUMENTED_ARGS = mapOf("wifi.connect" to setOf("ssid", "security", "psk"))

        /** The commands of a scanner from before recording control. */
        val OLD_COMMANDS = listOf("hello", "status.get", "info.get")

        fun defaultHello(protocol: Int = 1, commands: List<String> = CURRENT_COMMANDS): JsonObject = buildJsonObject {
            put("protocol", protocol)
            put("server", "dLidar")
            put("commands", buildJsonArray { commands.forEach { add(JsonPrimitive(it)) } })
            put("events", buildJsonArray {
                listOf("state", "warning", "progress", "summary").forEach { add(JsonPrimitive(it)) }
            })
            put("limits", buildJsonObject {
                put("max_request_bytes", 4096)
                put("max_message_bytes", 16384)
            })
        }
    }
}
