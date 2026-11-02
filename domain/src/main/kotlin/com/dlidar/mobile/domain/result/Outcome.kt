package com.dlidar.mobile.domain.result

/** Result of an operation that can fail. Failures are values; no exception crosses a layer. */
sealed interface Outcome<out T> {
    data class Ok<out T>(val value: T) : Outcome<T>
    data class Failed(val error: DomainError) : Outcome<Nothing>
}
