package com.desertcomm.modem

import android.media.AudioAttributes
import android.media.AudioFormat
import android.annotation.SuppressLint
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import com.desertcomm.core.NativeBridgeV3

object SubNoiseAudioModem {
    const val SAMPLE_RATE = 12_000
    const val BASE_FREQ_HZ = 1500f

    fun transmitTextOverAudio(payload: ByteArray): Result<Int> = runCatching {
        val pcm = NativeBridgeV3.encodeSubNoise8FskPcm(payload, BASE_FREQ_HZ, SAMPLE_RATE)
            ?: error("فشل توليد موجة 8-FSK")

        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(pcm.size * 2)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcm, 0, pcm.size)
        track.play()
        val durationMs = (pcm.size * 1000L) / SAMPLE_RATE
        Thread {
            Thread.sleep(durationMs + 300L)
            runCatching {
                track.stop()
                track.release()
            }
        }.start()
        pcm.size
    }

    @SuppressLint("MissingPermission")
    fun listenOnceOverMic(durationSec: Int = 13, expectedBytes: Int = 20): Result<String> = runCatching {
        val totalSamples = SAMPLE_RATE * durationSec
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(SAMPLE_RATE * 2)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf
        )
        val pcm = ShortArray(totalSamples)
        var offset = 0
        recorder.startRecording()
        try {
            while (offset < totalSamples) {
                val read = recorder.read(pcm, offset, (totalSamples - offset).coerceAtMost(2048))
                if (read <= 0) break
                offset += read
            }
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        val decoded = NativeBridgeV3.decodeSubNoise8FskPcm(pcm, BASE_FREQ_HZ, SAMPLE_RATE, expectedBytes)
            ?: error("لم يتم التقاط إطار 8-FSK صالح")
        String(decoded, Charsets.UTF_8).trimEnd('\u0000')
    }
}