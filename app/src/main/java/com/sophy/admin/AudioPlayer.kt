package com.sophy.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

class AudioPlayer {
    private var track: AudioTrack? = null

    @Synchronized
    fun start() {
        if (track != null) return
        val min = AudioTrack.getMinBufferSize(
            Protocol.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val size = maxOf(min * 2, Protocol.SAMPLE_RATE / 2)
        val format = AudioFormat.Builder()
            .setSampleRate(Protocol.SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        track = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track?.play()
    }

    fun write(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        try {
            start()
            var offset = 0
            while (offset < pcm.size) {
                val written = track?.write(pcm, offset, pcm.size - offset) ?: 0
                if (written <= 0) break
                offset += written
            }
        } catch (t: Throwable) {
            Log.e("SophyAudio", "AudioTrack write failed", t)
        }
    }

    @Synchronized
    fun stop() {
        try { track?.pause() } catch (_: Throwable) {}
        try { track?.flush() } catch (_: Throwable) {}
        try { track?.release() } catch (_: Throwable) {}
        track = null
    }
}
