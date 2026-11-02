package com.dlidar.mobile.domain.port

import java.time.Instant

fun interface Clock {
    fun now(): Instant
}
