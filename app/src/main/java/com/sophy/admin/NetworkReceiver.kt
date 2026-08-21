package com.sophy.receiver

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketException

class NetworkReceiver(
    private val onPeer: (String) -> Unit
) {
    companion object { const val PORT = 45678 }

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start(player: AudioPlayer) {
        if (running) return
        running = true
        thread = Thread({
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(PORT))
                    soTimeout = 1_000
                    receiveBufferSize = 64 * 1024
                }
                val buffer = ByteArray(Protocol.MAX_PACKET)
                val packet = DatagramPacket(buffer, buffer.size)
                while (running) {
                    try {
                        packet.length = buffer.size
                        socket?.receive(packet)
                        val decoded = Protocol.decode(packet.data, packet.length) ?: continue
                        val peer = "${packet.address.hostAddress}:${packet.port}"
                        ReceiverState.connected.set(true)
                        ReceiverState.peer = peer
                        ReceiverState.lastPacketAt.set(System.currentTimeMillis())
                        onPeer(peer)
                        when (decoded.type) {
                            Protocol.TYPE_AUDIO -> {
                                if (decoded.codec == Protocol.CODEC_PCM16 &&
                                    decoded.sampleRate == Protocol.SAMPLE_RATE &&
                                    decoded.channels == Protocol.CHANNELS) {
                                    player.write(decoded.payload)
                                    ReceiverState.packets.incrementAndGet()
                                }
                            }
                            Protocol.TYPE_PING -> {
                                try {
                                    val pong = Protocol.pong()
                                    socket?.send(DatagramPacket(pong, pong.size, packet.address, packet.port))
                                } catch (_: Throwable) {}
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        if (System.currentTimeMillis() - ReceiverState.lastPacketAt.get() > 3_000) {
                            ReceiverState.connected.set(false)
                            ReceiverState.peer = "بانتظار الهاتف…"
                        }
                    }
                }
            } catch (e: SocketException) {
                if (running) Log.e("SophyNet", "Socket error", e)
            } catch (t: Throwable) {
                if (running) Log.e("SophyNet", "Receiver error", t)
            } finally {
                try { socket?.close() } catch (_: Throwable) {}
                socket = null
            }
        }, "Sophy-UDP")
        thread?.start()
    }

    fun stop() {
        running = false
        try { socket?.close() } catch (_: Throwable) {}
        try { thread?.join(800) } catch (_: InterruptedException) {}
        thread = null
    }
}
