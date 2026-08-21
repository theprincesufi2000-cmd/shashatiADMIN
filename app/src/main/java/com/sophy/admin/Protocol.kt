package com.sophy.receiver

import java.nio.ByteBuffer
import java.nio.ByteOrder

object Protocol {
    const val MAGIC = 0x53504859 // "SPHY"
    const val VERSION: Byte = 1
    const val TYPE_AUDIO: Byte = 1
    const val TYPE_PING: Byte = 2
    const val TYPE_PONG: Byte = 3
    const val CODEC_PCM16: Byte = 0

    const val SAMPLE_RATE = 24_000
    const val CHANNELS = 1
    const val MAX_PACKET = 1_300
    const val HEADER_SIZE = 26

    data class Packet(
        val type: Byte,
        val codec: Byte,
        val channels: Int,
        val sampleRate: Int,
        val sequence: Long,
        val timestampUs: Long,
        val payload: ByteArray
    )

    fun decode(data: ByteArray, length: Int): Packet? {
        if (length < HEADER_SIZE || length > MAX_PACKET) return null
        val b = ByteBuffer.wrap(data, 0, length).order(ByteOrder.BIG_ENDIAN)
        if (b.int != MAGIC) return null
        if (b.get() != VERSION) return null
        val type = b.get()
        val codec = b.get()
        val channels = b.get().toInt() and 0xFF
        val sampleRate = b.int
        val sequence = b.int.toLong() and 0xFFFF_FFFFL
        val timestampUs = b.long
        val payloadLength = b.short.toInt() and 0xFFFF
        if (payloadLength != length - HEADER_SIZE) return null
        if (payloadLength <= 0 && type == TYPE_AUDIO) return null
        val payload = ByteArray(payloadLength)
        b.get(payload)
        return Packet(type, codec, channels, sampleRate, sequence, timestampUs, payload)
    }

    fun pong(): ByteArray {
        val b = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        b.putInt(MAGIC)
        b.put(VERSION)
        b.put(TYPE_PONG)
        b.put(CODEC_PCM16)
        b.put(CHANNELS.toByte())
        b.putInt(SAMPLE_RATE)
        b.putInt(0)
        b.putLong(System.nanoTime() / 1_000)
        b.putShort(0)
        return b.array()
    }
}
