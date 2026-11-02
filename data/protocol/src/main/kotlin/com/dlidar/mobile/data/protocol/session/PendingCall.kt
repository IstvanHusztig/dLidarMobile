package com.dlidar.mobile.data.protocol.session

import com.dlidar.mobile.data.protocol.codec.ResponseBody
import com.dlidar.mobile.data.protocol.command.ProtocolCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.JsonObject

/** A sent request waiting for its response. */
internal class PendingCall<R>(
    val command: ProtocolCommand<R>,
    private val onAnswer: ((R) -> Unit)?,
) {
    val result = CompletableDeferred<CallResult<R>>()

    /** Set by the dispatcher on registration. */
    @Volatile var id: Int? = null

    fun deliver(body: ResponseBody) {
        val outcome = when (body) {
            is ResponseBody.Ok -> decode(body.result)
            is ResponseBody.Err -> CallResult.Failure(CallFailure.Remote(body.error))
        }
        if (outcome is CallResult.Success) onAnswer?.invoke(outcome.value)
        result.complete(outcome)
    }

    fun fail(failure: CallFailure) {
        result.complete(CallResult.Failure(failure))
    }

    private fun decode(json: JsonObject): CallResult<R> = try {
        CallResult.Success(command.decode(json))
    } catch (e: IllegalArgumentException) {
        CallResult.Failure(CallFailure.BadResult(e.message ?: "result does not fit ${command.name}"))
    }
}
