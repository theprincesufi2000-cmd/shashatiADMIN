package com.sophy.admin

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Must stay binary-compatible with Sophy Receiver. */
object Protocol {
    const val MAGIC = 0x53504859 // SPHY
    const val VERSION: Byte = 1
    const val TYPE_AUDIO: Byte = 1
    const val TYPE_PING: Byte = 2
    const val TYPE_PONG: Byte = 3
    const val CODEC_PCM16: Byte = 0

    const val SAMPLE_RATE = 24_000
    const val CHANNELS = 1
    const val MAX_PACKET = 1_300
    const val HEADER_SIZE = 26
    const val AUDIO_BYTES_PER_FRAME = 960 // 20 ms @ 24 kHz mono PCM16

    fun audio(sequence: Long, timestampUs: Long, pcm: ByteArray, length: Int): ByteArray {
        require(length in 1..AUDIO_BYTES_PER_FRAME)
        val out = ByteArray(HEADER_SIZE + length)
        val b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        b.putInt(MAGIC)
        b.put(VERSION)
        b.put(TYPE_AUDIO)
        b.put(CODEC_PCM16)
        b.put(CHANNELS.toByte())
        b.putInt(SAMPLE_RATE)
        b.putInt((sequence and 0xFFFF_FFFFL).toInt())
        b.putLong(timestampUs)
        b.putShort(length.toShort())
        System.arraycopy(pcm, 0, out, HEADER_SIZE, length)
        return out
    }

    fun ping(sequence: Long): ByteArray {
        val out = ByteArray(HEADER_SIZE)
        val b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        b.putInt(MAGIC)
        b.put(VERSION)
        b.put(TYPE_PING)
        b.put(CODEC_PCM16)
        b.put(CHANNELS.toByte())
        b.putInt(SAMPLE_RATE)
        b.putInt((sequence and 0xFFFF_FFFFL).toInt())
        b.putLong(System.nanoTime() / 1_000L)
        b.putShort(0)
        return out
    }
}
