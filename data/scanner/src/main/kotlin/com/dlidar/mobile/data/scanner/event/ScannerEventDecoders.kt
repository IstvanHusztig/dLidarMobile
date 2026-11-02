package com.dlidar.mobile.data.scanner.event

import com.dlidar.mobile.data.protocol.codec.ProtocolEvent
import com.dlidar.mobile.data.scanner.ProtocolMappers
import com.dlidar.mobile.data.scanner.ScannerInput
import com.dlidar.mobile.data.scanner.dto.ProgressDto
import com.dlidar.mobile.data.scanner.dto.ScannerJson
import com.dlidar.mobile.data.scanner.dto.StateEventDto
import com.dlidar.mobile.data.scanner.dto.SummaryDto
import com.dlidar.mobile.data.scanner.dto.WarningDto
import com.dlidar.mobile.domain.model.Warning
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** What a decoder needs besides the event data. */
internal class EventContext(val receivedAt: Instant, val newWarningId: () -> Long)

/** Turns one kind of event into an input. A new event is a new decoder (open/closed). */
internal interface ScannerEventDecoder {
    val name: String

    /** Throws [IllegalArgumentException] when the data does not fit. */
    fun decode(data: JsonObject, context: EventContext): ScannerInput
}

internal sealed interface EventDecoding {
    data class Decoded(val input: ScannerInput) : EventDecoding
    /** An event this app does not know. Ignored, as the protocol asks. */
    data object Unknown : EventDecoding
    data class Invalid(val reason: String) : EventDecoding
}

internal class ScannerEventDecoders(decoders: List<ScannerEventDecoder>) {
    private val byName = decoders.associateBy { it.name }

    fun decode(event: ProtocolEvent, context: EventContext): EventDecoding {
        val decoder = byName[event.name] ?: return EventDecoding.Unknown
        return try {
            EventDecoding.Decoded(decoder.decode(event.data, context))
        } catch (e: IllegalArgumentException) {
            EventDecoding.Invalid(e.message ?: "data does not fit ${event.name}")
        }
    }

    companion object {
        fun v1() = ScannerEventDecoders(listOf(StateEvent, WarningEvent, ProgressEvent, SummaryEvent))
    }
}

internal object StateEvent : ScannerEventDecoder {
    override val name = "state"
    override fun decode(data: JsonObject, context: EventContext) = ScannerInput.StateChanged(
        ProtocolMappers.status(ScannerJson.decodeFromJsonElement(StateEventDto.serializer(), data)),
    )
}

internal object WarningEvent : ScannerEventDecoder {
    override val name = "warning"
    override fun decode(data: JsonObject, context: EventContext): ScannerInput {
        val dto = ScannerJson.decodeFromJsonElement(WarningDto.serializer(), data)
        return ScannerInput.WarningRaised(
            Warning(context.newWarningId(), ProtocolMappers.warningKind(dto.kind), dto.text, context.receivedAt),
        )
    }
}

internal object ProgressEvent : ScannerEventDecoder {
    override val name = "progress"
    override fun decode(data: JsonObject, context: EventContext): ScannerInput {
        val dto = ScannerJson.decodeFromJsonElement(ProgressDto.serializer(), data)
        return ScannerInput.ProgressReported(dto.chunks, dto.points)
    }
}

internal object SummaryEvent : ScannerEventDecoder {
    override val name = "summary"
    override fun decode(data: JsonObject, context: EventContext) = ScannerInput.SummaryReceived(
        ProtocolMappers.summary(ScannerJson.decodeFromJsonElement(SummaryDto.serializer(), data), context.receivedAt),
    )
}
