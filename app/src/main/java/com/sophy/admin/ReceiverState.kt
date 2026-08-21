package com.sophy.receiver

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object ReceiverState {
    val running = AtomicBoolean(false)
    val connected = AtomicBoolean(false)
    val packets = AtomicLong(0)
    val lastPacketAt = AtomicLong(0)
    @Volatile var peer: String = "بانتظار الهاتف…"
}
