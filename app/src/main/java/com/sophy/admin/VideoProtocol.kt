package com.sophy.admin

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Binary protocol for camera streaming, shared with Sophy Receiver.
 * A single JPEG video frame is split into one or more UDP chunks so each
 * packet stays under a safe LAN MTU. The receiver reassembles chunks that
 * share the same frameId before decoding the JPEG.
 *
 * Header layout (30 bytes, big-endian):
 *   0  magic         Int32   0x53504859 ("SPHY")
 *   4  version       Byte
 *   5  type          Byte    TYPE_VIDEO
 *   6  rotation      Byte    reserved (frames are pre-rotated by the sender)
 *   7  reserved      Byte
 *   8  frameId       Int32   wraps around, identifies which frame a chunk belongs to
 *  12  chunkIndex    UInt16  0-based index of this chunk within the frame
 *  14  chunkCount    UInt16  total number of chunks for this frame
 *  16  timestampUs   Int64   capture timestamp
 *  24  frameWidth    UInt16
 *  26  frameHeight   UInt16
 *  28  payloadLength UInt16
 *  30  payload...
 */
object VideoProtocol {
    const val MAGIC = 0x53504859 // SPHY
    const val VERSION: Byte = 1
    const val TYPE_VIDEO: Byte = 4
    const val HEADER_SIZE = 30

    // Kept comfortably under a typical 1472-byte Wi‑Fi MTU payload.
    const val CHUNK_PAYLOAD = 1300

    fun chunk(
        frameId: Int,
        chunkIndex: Int,
        chunkCount: Int,
        timestampUs: Long,
        width: Int,
        height: Int,
        data: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray {
        require(length in 1..CHUNK_PAYLOAD)
        val out = ByteArray(HEADER_SIZE + length)
        val b = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN)
        b.putInt(MAGIC)
        b.put(VERSION)
        b.put(TYPE_VIDEO)
        b.put(0) // rotation reserved
        b.put(0) // reserved
        b.putInt(frameId)
        b.putShort(chunkIndex.toShort())
        b.putShort(chunkCount.toShort())
        b.putLong(timestampUs)
        b.putShort(width.toShort())
        b.putShort(height.toShort())
        b.putShort(length.toShort())
        System.arraycopy(data, offset, out, HEADER_SIZE, length)
        return out
    }
}
