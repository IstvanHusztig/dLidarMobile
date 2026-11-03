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
 * recording.start and recording.stop (only when hello lists them, as an older scanner answers
 * unknown_command), numbers events from 1 per connection, and splits
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
        if (request["args"] != null) {
            violations += "undocumented args for $cmd: $line"
            return respondError(id, "bad_request", "unknown arg")
        }
        scriptedErrors[cmd]?.removeFirstOrNull()?.let { code -> return respondError(id, code, code) }
        when (cmd) {
            "hello" -> hello?.let { respond(id, it) } ?: respondError(id, "unknown_command", "unknown command")
            "status.get" -> respond(id, statusResult())
            "info.get" -> respond(id, info)
            "recording.start" -> if (lists(cmd)) recordingStart(id) else respondError(id, "unknown_command", "unknown command")
            "recording.stop" -> if (lists(cmd)) recordingStop(id) else respondError(id, "unknown_command", "unknown command")
            else -> respondError(id, "unknown_command", "unknown command")
        }
    }

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

        val CURRENT_COMMANDS = listOf("hello", "status.get", "info.get", "recording.start", "recording.stop")

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
