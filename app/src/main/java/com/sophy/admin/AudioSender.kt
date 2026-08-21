package com.sophy.admin

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

class AudioSender(
    private val onStateChanged: () -> Unit
) {
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var socket: DatagramSocket? = null
    @Volatile private var host: String = ""
    @Volatile private var port: Int = 45678
    private var sequence = 0L

    fun setTarget(host: String, port: Int) {
        this.host = host
        this.port = port
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        AdminState.broadcasting.set(true)
        AdminState.status = "البث الصوتي يعمل"
        AdminState.error = ""
        onStateChanged()

        thread = Thread({ captureLoop() }, "SophyAudioSender").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun captureLoop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            AudioProtocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            fail("الميكروفون غير متاح")
            return
        }

        val frameBytes = AudioProtocol.AUDIO_BYTES_PER_FRAME
        val bufferSize = maxOf(minBuffer, frameBytes * 8)
        val pcm = ByteArray(frameBytes)

        try {
            val address = InetAddress.getByName(host)
            val udp = DatagramSocket().apply {
                trafficClass = 0x10
                sendBufferSize = 256 * 1024
            }
            socket = udp

            val audio = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AudioProtocol.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
            record = audio

            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                fail("تعذر تهيئة الميكروفون")
                return
            }

            audio.startRecording()
            while (running.get()) {
                var filled = 0
                while (filled < frameBytes && running.get()) {
                    val n = audio.read(pcm, filled, frameBytes - filled, AudioRecord.READ_BLOCKING)
                    if (n <= 0) {
                        fail("تعذر قراءة الميكروفون")
                        return
                    }
                    filled += n
                }
                if (!running.get()) break

                val packet = AudioProtocol.audio(
                    sequence = sequence++, 
                    timestampUs = System.nanoTime() / 1_000L,
                    pcm = pcm,
                    length = filled
                )
                udp.send(DatagramPacket(packet, packet.size, address, port))
                AdminState.packets.incrementAndGet()
                AdminState.lastPacketAt.set(System.currentTimeMillis())
            }
        } catch (t: Throwable) {
            if (running.get()) fail(t.message ?: "فشل إرسال الصوت")
        } finally {
            try { record?.stop() } catch (_: Throwable) {}
            try { record?.release() } catch (_: Throwable) {}
            try { socket?.close() } catch (_: Throwable) {}
            record = null
            socket = null
            AdminState.broadcasting.set(false)
            if (AdminState.error.isEmpty()) AdminState.status = "البث متوقف"
            onStateChanged()
        }
    }

    private fun fail(message: String) {
        Log.e("SophyAudio", message)
        AdminState.error = message
        AdminState.status = "خطأ في البث"
        running.set(false)
        onStateChanged()
    }

    fun stop() {
        running.set(false)
        try { record?.stop() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        thread?.interrupt()
        thread = null
        AdminState.broadcasting.set(false)
        if (AdminState.error.isEmpty()) AdminState.status = "البث متوقف"
        onStateChanged()
    }
}
