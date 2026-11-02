package com.dlidar.mobile.infra.ble

import java.util.UUID

/**
 * The scanner's GATT profile. The UUIDs are provisional and may change once before v1 is frozen;
 * this is the only place they are written down.
 */
object BleUuids {
    val SERVICE: UUID = UUID.fromString("b06b0001-1294-4457-85c4-ef6a46d0c127")

    /** The app writes here (write and write-without-response). */
    val RX: UUID = UUID.fromString("b06b0002-1294-4457-85c4-ef6a46d0c127")

    /** The scanner notifies here. */
    val TX: UUID = UUID.fromString("b06b0003-1294-4457-85c4-ef6a46d0c127")

    /** Client Characteristic Configuration descriptor (Bluetooth SIG). */
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val REQUESTED_MTU = 247
    const val DEFAULT_MTU = 23
}
