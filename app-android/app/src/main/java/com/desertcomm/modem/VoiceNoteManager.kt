package com.desertcomm.modem

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class VoicePlaybackState(
    val playingUid: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 1,
    val speed: Float = 1.0f
)

object VoiceNoteManager {
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var player: MediaPlayer? = null
    private var activeTempFile: File? = null
    private var autoStopped = false
    private val handler = Handler(Looper.getMainLooper())

    private val _playback = MutableStateFlow(VoicePlaybackState())
    val playback: StateFlow<VoicePlaybackState> = _playback.asStateFlow()

    private val progressTicker = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && _playback.value.isPlaying) {
                runCatching {
                    _playback.value = _playback.value.copy(
                        positionMs = p.currentPosition.coerceAtLeast(0),
                        durationMs = p.duration.coerceAtLeast(1)
                    )
                }
                handler.postDelayed(this, 120L)
            }
        }
    }

    fun startRecording(context: Context, isOnlineUnlimited: Boolean): Result<Unit> = runCatching {
        cancelRecording()
        autoStopped = false
        val ext = if (isOnlineUnlimited) "m4a" else "3gp"
        val outFile = File(context.cacheDir, "sada_voice_${System.currentTimeMillis()}.$ext")
        currentFile = outFile
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        rec.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            if (isOnlineUnlimited) {
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(32_000)
                setAudioSamplingRate(16_000)
                setMaxDuration(600_000)
            } else {
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setAudioEncodingBitRate(4750)
                setAudioSamplingRate(8000)
                setMaxDuration(29_000)
            }
            setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                    autoStopped = true
                }
            }
            setOutputFile(outFile.absolutePath)
            prepare()
            start()
        }
        recorder = rec
    }

    fun cancelRecording() {
        val rec = recorder
        recorder = null
        if (rec != null) {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        currentFile?.delete()
        currentFile = null
        autoStopped = false
    }

    fun stopRecordingBytes(): ByteArray? {
        val rec = recorder
        val file = currentFile
        recorder = null
        currentFile = null
        if (rec != null) {
            if (!autoStopped) {
                runCatching { rec.stop() }
            }
            runCatching { rec.release() }
        }
        autoStopped = false
        val bytes = runCatching { file?.takeIf { it.exists() && it.length() > 100 }?.readBytes() }.getOrNull()
        runCatching { file?.delete() }
        return bytes?.takeIf { it.isNotEmpty() }
    }

    fun togglePlayFile(uid: String, file: File): Result<Unit> = runCatching {
        val cur = _playback.value
        if (cur.playingUid == uid && player != null) {
            if (cur.isPlaying) {
                player?.pause()
                handler.removeCallbacks(progressTicker)
                _playback.value = cur.copy(isPlaying = false)
            } else {
                player?.start()
                _playback.value = cur.copy(isPlaying = true)
                handler.post(progressTicker)
            }
            return@runCatching
        }

        stopPlayback()
        val mp = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            prepare()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { playbackParams = PlaybackParams().setSpeed(_playback.value.speed) }
            }
            setOnCompletionListener {
                handler.removeCallbacks(progressTicker)
                _playback.value = VoicePlaybackState(speed = _playback.value.speed)
                runCatching { it.release() }
                if (player == it) player = null
            }
            start()
        }
        player = mp
        _playback.value = VoicePlaybackState(
            playingUid = uid,
            isPlaying = true,
            positionMs = 0,
            durationMs = mp.duration.coerceAtLeast(1),
            speed = _playback.value.speed
        )
        handler.post(progressTicker)
    }

    fun togglePlayBase64(context: Context, uid: String, b64: String): Result<Unit> = runCatching {
        val cur = _playback.value
        if (cur.playingUid == uid && player != null) {
            togglePlayFile(uid, activeTempFile ?: error("no file"))
            return@runCatching
        }
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val tmp = File(context.cacheDir, "sada_play_$uid.audio")
        tmp.writeBytes(bytes)
        activeTempFile = tmp
        togglePlayFile(uid, tmp).getOrThrow()
    }

    fun cycleSpeed() {
        val next = when (_playback.value.speed) {
            1.0f -> 1.5f
            1.5f -> 2.0f
            else -> 1.0f
        }
        _playback.value = _playback.value.copy(speed = next)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching { player?.playbackParams = PlaybackParams().setSpeed(next) }
        }
    }

    fun seekTo(ms: Int) {
        runCatching {
            player?.seekTo(ms)
            _playback.value = _playback.value.copy(positionMs = ms)
        }
    }

    fun stopPlayback() {
        handler.removeCallbacks(progressTicker)
        runCatching { player?.stop(); player?.release() }
        player = null
        _playback.value = VoicePlaybackState(speed = _playback.value.speed)
    }
}