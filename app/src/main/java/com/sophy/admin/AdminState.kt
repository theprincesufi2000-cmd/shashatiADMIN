package com.sophy.admin

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

object AdminState {
    val serviceRunning = AtomicBoolean(false)
    val discovering = AtomicBoolean(false)
    val receiverFound = AtomicBoolean(false)
    val broadcasting = AtomicBoolean(false)
    val packets = AtomicLong(0)
    val lastPacketAt = AtomicLong(0)

    @Volatile var receiverName: String = "البحث عن الشاشة…"
    @Volatile var receiverHost: String = ""
    @Volatile var receiverPort: Int = 45678
    @Volatile var status: String = "جاهز"
    @Volatile var error: String = ""
}
