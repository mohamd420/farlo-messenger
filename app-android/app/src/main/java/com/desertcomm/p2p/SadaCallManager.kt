package com.desertcomm.p2p

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import com.desertcomm.MainActivity
import com.desertcomm.core.ChatRepository
import com.desertcomm.core.SendTransportMode
import com.desertcomm.modem.VoiceNoteManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

enum class CallPhase { IDLE, OUTGOING, INCOMING, ACTIVE }

data class CallUiState(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String = "",
    val peerId: String = "",
    val peerName: String = "",
    val isMuted: Boolean = false,
    val isSpeaker: Boolean = false,
    val durationSec: Int = 0,
    val statusText: String = ""
)

data class LivePttState(
    val isChannelOpen: Boolean = false,
    val isTransmitting: Boolean = false,
    val isReceiving: Boolean = false,
    val peerId: String = ""
)

object SadaCallManager {
    private const val CALL_NOTIF_CHANNEL = "sada_incoming_calls_v1"
    private const val CALL_NOTIF_ID = 4401

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        PeerConnection.IceServer.builder("stun:standard.relay.metered.ca:80").createIceServer()
    )

    private val _state = MutableStateFlow(CallUiState())
    val state: StateFlow<CallUiState> = _state.asStateFlow()

    private val _pttState = MutableStateFlow(LivePttState())
    val pttState: StateFlow<LivePttState> = _pttState.asStateFlow()

    private val callExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val endedCallIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val pendingRemoteIce = Collections.synchronizedList(mutableListOf<IceCandidate>())

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var pendingOfferSdp: String? = null
    private var remoteDescSet = false
    private var ringTone: ToneGenerator? = null
    private var proximityLock: android.os.PowerManager.WakeLock? = null

    private fun updateProximitySensor(appContext: Context, active: Boolean) {
        runCatching {
            if (active && !_state.value.isSpeaker) {
                if (proximityLock?.isHeld != true) {
                    val pm = appContext.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                    if (pm.isWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                        proximityLock = pm.newWakeLock(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "DesertComm::CallProximity").apply {
                            acquire(60 * 60 * 1000L)
                        }
                    }
                }
            } else {
                proximityLock?.let { if (it.isHeld) it.release() }
                proximityLock = null
            }
        }
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            if (_state.value.phase == CallPhase.ACTIVE) {
                _state.value = _state.value.copy(durationSec = _state.value.durationSec + 1)
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private fun gzipB64(text: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    private fun ungzipB64(b64: String): String {
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        return GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    @Suppress("DEPRECATION")
    private fun showIncomingCallNotification(appContext: Context, callerName: String) {
        runCatching {
            val nm = appContext.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val ch = NotificationChannel(CALL_NOTIF_CHANNEL, "مكالمات فارلو الواردة", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "إشعارات المكالمات الصوتية المشفّرة"
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                nm.createNotificationChannel(ch)
            }
            val openIntent = Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(appContext, 4402, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(appContext, CALL_NOTIF_CHANNEL) else Notification.Builder(appContext)
            val notif = b.setContentTitle("مكالمة مشفّرة واردة • فارلو")
                .setContentText("$callerName يتصل بك الآن")
                .setSmallIcon(android.R.drawable.sym_call_incoming)
                .setCategory(Notification.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(CALL_NOTIF_ID, notif)
        }
    }

    private fun cancelCallNotification(appContext: Context) {
        runCatching { appContext.getSystemService(NotificationManager::class.java)?.cancel(CALL_NOTIF_ID) }
    }

    private fun ensureFactory(appContext: Context): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext).setEnableInternalTracer(false).createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val f = PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
        adm.release()
        factory = f
        return f
    }

    private fun flushPendingRemoteIce() {
        val pc = peerConnection ?: return
        synchronized(pendingRemoteIce) {
            pendingRemoteIce.forEach { c -> runCatching { pc.addIceCandidate(c) } }
            pendingRemoteIce.clear()
        }
    }

    private fun createPeerConnection(appContext: Context, cid: String, peerId: String, sendMode: SendTransportMode): PeerConnection {
        val f = ensureFactory(appContext)
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val pc = f.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(s: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState?) {
                mainHandler.post { if (_state.value.phase != CallPhase.IDLE) _state.value = _state.value.copy(statusText = "ICE: ${s?.name}") }
                if (s == PeerConnection.IceConnectionState.CONNECTED || s == PeerConnection.IceConnectionState.COMPLETED) {
                    mainHandler.post {
                        stopRingTone()
                        cancelCallNotification(appContext)
                        if (_state.value.phase != CallPhase.IDLE) {
                            _state.value = _state.value.copy(phase = CallPhase.ACTIVE)
                            mainHandler.removeCallbacks(timerRunnable)
                            mainHandler.postDelayed(timerRunnable, 1000L)
                        }
                    }
                } else if (s == PeerConnection.IceConnectionState.DISCONNECTED || s == PeerConnection.IceConnectionState.FAILED || s == PeerConnection.IceConnectionState.CLOSED) {
                    mainHandler.postDelayed({
                        val stNow = peerConnection?.iceConnectionState()
                        if (_state.value.phase == CallPhase.ACTIVE && (stNow == null || stNow == PeerConnection.IceConnectionState.DISCONNECTED || stNow == PeerConnection.IceConnectionState.FAILED || stNow == PeerConnection.IceConnectionState.CLOSED)) {
                            cleanupCall(appContext)
                        }
                    }, 4500L)
                }
            }
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(c: IceCandidate?) { /* bundled inside SDP after ICE gather */ }
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>?) {}
            override fun onAddStream(s: MediaStream?) {}
            override fun onRemoveStream(s: MediaStream?) {}
            override fun onDataChannel(d: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver?, streams: Array<out MediaStream>?) { (r?.track() as? AudioTrack)?.setEnabled(true); mainHandler.post { _state.value = _state.value.copy(statusText = "وصل مسار الصوت 🔊") } }
        }) ?: error("فشل تهيئة WebRTC")

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        }
        val src = f.createAudioSource(audioConstraints)
        val track = f.createAudioTrack("sada_opus_audio", src)
        track.setEnabled(true)
        pc.addTrack(track, listOf("sada_stream"))
        audioSource = src
        localAudioTrack = track
        return pc
    }

    private fun startRingTone(isIncoming: Boolean) {
        stopRingTone()
        runCatching {
            ringTone = ToneGenerator(AudioManager.STREAM_RING, 70).apply {
                startTone(if (isIncoming) ToneGenerator.TONE_CDMA_CALL_SIGNAL_ISDN_NORMAL else ToneGenerator.TONE_SUP_RINGTONE, 25000)
            }
        }
    }

    private fun stopRingTone() {
        runCatching { ringTone?.stopTone(); ringTone?.release() }
        ringTone = null
    }

    private fun setAudioModeInCall(appContext: Context, inCall: Boolean) {
        runCatching {
            val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = if (inCall) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = if (inCall) _state.value.isSpeaker else false
        }
    }

    fun startOutgoingCall(context: Context, peerId: String, peerName: String, sendMode: SendTransportMode) {
        if (_state.value.phase != CallPhase.IDLE) return
        val appCtx = context.applicationContext
        val cid = UUID.randomUUID().toString().replace("-", "").take(10)
        remoteDescSet = false
        pendingRemoteIce.clear()
        _state.value = CallUiState(CallPhase.OUTGOING, cid, peerId, peerName)
        startRingTone(isIncoming = false)
        mainHandler.postDelayed({ if (_state.value.phase == CallPhase.OUTGOING && _state.value.callId == cid) cleanupCall(appCtx) }, 40_000L)

        callExecutor.execute {
            runCatching {
                setAudioModeInCall(appCtx, true)
                updateProximitySensor(appCtx, true)
                val pc = createPeerConnection(appCtx, cid, peerId, sendMode)
                peerConnection = pc
                val constraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                }
                pc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription?) {
                        if (desc == null) return
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                val nowSec = System.currentTimeMillis() / 1000L
                                mainHandler.postDelayed({ callExecutor.execute { val fullSdp = peerConnection?.localDescription?.description ?: desc.description; val ts = System.currentTimeMillis() / 1000L; ChatRepository.sendMessage(appCtx, peerId, "WEBRTC_OFFER|$cid|$ts|${gzipB64(fullSdp)}", sendMode); mainHandler.post { _state.value = _state.value.copy(statusText = "تم إرسال الدعوة (${fullSdp.length}B)") } } }, 2200L)
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {}
                        }, desc)
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(err: String?) { mainHandler.post { cleanupCall(appCtx) } }
                    override fun onSetFailure(err: String?) {}
                }, constraints)
            }.onFailure { err ->
                mainHandler.post { Toast.makeText(appCtx, "خطأ WebRTC: ${err.message}", Toast.LENGTH_LONG).show(); cleanupCall(appCtx) }
            }
        }
    }

    fun onIncomingSignal(context: Context, senderId: String, senderName: String, body: String) {
        val appCtx = context.applicationContext
        val nowSec = System.currentTimeMillis() / 1000L
        when {
            body.startsWith("WEBRTC_OFFER|") -> {
                val p = body.split("|", limit = 4)
                if (p.size < 4) return
                val cid = p[1]
                val ts = p[2].toLongOrNull() ?: 0L
                if (kotlin.math.abs(nowSec - ts) > 45L || endedCallIds.contains(cid)) return
                if (_state.value.phase != CallPhase.IDLE) return
                val sdp = runCatching { ungzipB64(p[3]) }.getOrNull() ?: return
                mainHandler.post { _state.value = _state.value.copy(statusText = "تم استلام SDP بنجاح") }
                pendingOfferSdp = sdp
                remoteDescSet = false
                pendingRemoteIce.clear()
                _state.value = CallUiState(CallPhase.INCOMING, cid, senderId, senderName)
                startRingTone(isIncoming = true)
                showIncomingCallNotification(appCtx, senderName)
            }
            body.startsWith("WEBRTC_ANSWER|") -> {
                val p = body.split("|", limit = 4)
                if (p.size < 4) return
                val cid = p[1]
                if (_state.value.phase != CallPhase.OUTGOING || _state.value.callId != cid) return
                val sdp = runCatching { ungzipB64(p[3]) }.getOrNull() ?: return
                mainHandler.post { _state.value = _state.value.copy(statusText = "تم استلام SDP بنجاح") }
                stopRingTone()
                callExecutor.execute {
                    peerConnection?.setRemoteDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            remoteDescSet = true
                            flushPendingRemoteIce()
                            mainHandler.post {
                                _state.value = _state.value.copy(phase = CallPhase.ACTIVE, durationSec = 0)
                                mainHandler.removeCallbacks(timerRunnable)
                                mainHandler.postDelayed(timerRunnable, 1000L)
                            }
                        }
                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                        override fun onSetFailure(p0: String?) {}
                    }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
                }
            }
            body.startsWith("WEBRTC_ICE|") -> {
                val p = body.split("|", limit = 5)
                if (p.size < 5) return
                val cid = p[1]
                if (_state.value.callId != cid) return
                val sdpMid = p[2]
                val idx = p[3].toIntOrNull() ?: 0
                val candSdp = runCatching { String(Base64.decode(p[4], Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull() ?: return
                val ice = IceCandidate(sdpMid, idx, candSdp)
                callExecutor.execute {
                    if (remoteDescSet && peerConnection != null) {
                        runCatching { peerConnection?.addIceCandidate(ice) }
                    } else {
                        pendingRemoteIce.add(ice)
                    }
                }
            }
            body.startsWith("WEBRTC_END|") -> {
                val cid = body.split("|").getOrNull(1)?.trim().orEmpty()
                if (cid.isNotEmpty()) endedCallIds.add(cid)
                val cur = _state.value
                if (cur.phase != CallPhase.IDLE && (cur.callId == cid || cur.peerId.equals(senderId, ignoreCase = true))) {
                    cleanupCall(appCtx)
                }
            }
        }
    }

    fun acceptIncomingCall(context: Context, sendMode: SendTransportMode) {
        val st = _state.value
        val offerSdp = pendingOfferSdp ?: return
        if (st.phase != CallPhase.INCOMING) return
        val appCtx = context.applicationContext
        val cid = st.callId
        stopRingTone()
        cancelCallNotification(appCtx)
        _state.value = st.copy(phase = CallPhase.ACTIVE, durationSec = 0)

        callExecutor.execute {
            runCatching {
                setAudioModeInCall(appCtx, true)
                updateProximitySensor(appCtx, true)
                val pc = createPeerConnection(appCtx, cid, st.peerId, sendMode)
                peerConnection = pc
                pc.setRemoteDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        remoteDescSet = true
                        flushPendingRemoteIce()
                        val constraints = MediaConstraints().apply {
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
                        }
                        pc.createAnswer(object : SdpObserver {
                            override fun onCreateSuccess(desc: SessionDescription?) {
                                if (desc == null) return
                                pc.setLocalDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        val nowSec = System.currentTimeMillis() / 1000L
                                        mainHandler.postDelayed({ callExecutor.execute { val fullSdp = peerConnection?.localDescription?.description ?: desc.description; val ts = System.currentTimeMillis() / 1000L; ChatRepository.sendMessage(appCtx, st.peerId, "WEBRTC_ANSWER|$cid|$ts|${gzipB64(fullSdp)}", sendMode); mainHandler.post { _state.value = _state.value.copy(statusText = "تم إرسال الرد (${fullSdp.length}B)") } } }, 2200L)
                                    }
                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onCreateFailure(p0: String?) {}
                                    override fun onSetFailure(p0: String?) {}
                                }, desc)
                            }
                            override fun onSetSuccess() {}
                            override fun onCreateFailure(err: String?) {}
                            override fun onSetFailure(err: String?) {}
                        }, constraints)
                    }
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
            }.onFailure { err ->
                mainHandler.post { Toast.makeText(appCtx, "فشل الرد: ${err.message}", Toast.LENGTH_LONG).show(); cleanupCall(appCtx) }
            }
        }
    }

    fun endCall(context: Context, sendMode: SendTransportMode) {
        val st = _state.value
        val appCtx = context.applicationContext
        if (st.callId.isNotEmpty()) endedCallIds.add(st.callId)
        if (st.phase != CallPhase.IDLE && st.peerId.isNotEmpty()) {
            val nowSec = System.currentTimeMillis() / 1000L
            ChatRepository.sendMessage(appCtx, st.peerId, "WEBRTC_END|${st.callId}|$nowSec", sendMode)
            mainHandler.postDelayed({ ChatRepository.sendMessage(appCtx, st.peerId, "WEBRTC_END|${st.callId}|$nowSec", sendMode) }, 1200L)
        }
        cleanupCall(appCtx)
    }

    fun toggleMute() {
        val next = !_state.value.isMuted
        _state.value = _state.value.copy(isMuted = next)
        callExecutor.execute { localAudioTrack?.setEnabled(!next) }
    }

    fun toggleSpeaker(context: Context) {
        val next = !_state.value.isSpeaker
        _state.value = _state.value.copy(isSpeaker = next)
        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = next
            updateProximitySensor(context.applicationContext, _state.value.phase == CallPhase.ACTIVE || _state.value.phase == CallPhase.OUTGOING)
        }
    }

    private fun cleanupCall(appContext: Context) {
        stopRingTone()
        cancelCallNotification(appContext)
        mainHandler.removeCallbacks(timerRunnable)
        pendingOfferSdp = null
        remoteDescSet = false
        pendingRemoteIce.clear()
        val curId = _state.value.callId
        if (curId.isNotEmpty()) endedCallIds.add(curId)
        _state.value = CallUiState()
        callExecutor.execute {
            runCatching { localAudioTrack?.setEnabled(false) }
            runCatching { peerConnection?.close(); peerConnection?.dispose() }
            runCatching { audioSource?.dispose() }
            peerConnection = null
            audioSource = null
            localAudioTrack = null
            updateProximitySensor(appContext, false)
            setAudioModeInCall(appContext, false)
        }
    }

    private fun playRadioBeep(tone: Int) {
        runCatching {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 75)
            tg.startTone(tone, 110)
            mainHandler.postDelayed({ runCatching { tg.release() } }, 200L)
        }
    }

    fun startPttHold(context: Context) {
        playRadioBeep(ToneGenerator.TONE_PROP_BEEP)
        VoiceNoteManager.startRecording(context, isOnlineUnlimited = false)
            .onSuccess { _pttState.value = _pttState.value.copy(isTransmitting = true) }
            .onFailure { Toast.makeText(context, "امنح صلاحية الميكروفون", Toast.LENGTH_SHORT).show() }
    }

    fun stopPttHoldAndSend(context: Context, peerId: String, sendMode: SendTransportMode) {
        if (!_pttState.value.isTransmitting) return
        _pttState.value = _pttState.value.copy(isTransmitting = false)
        playRadioBeep(ToneGenerator.TONE_PROP_BEEP2)
        val bytes = VoiceNoteManager.stopRecordingBytes()
        if (bytes != null && bytes.size > 150) {
            val nowSec = System.currentTimeMillis() / 1000L
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            ChatRepository.sendMessage(context, peerId, "PTT_LIVE|$nowSec|$b64", sendMode)
        }
    }

    fun onIncomingPttLive(context: Context, uid: String, body: String) {
        val p = body.split("|", limit = 3)
        if (p.size < 3) return
        val ts = p[1].toLongOrNull() ?: 0L
        val nowSec = System.currentTimeMillis() / 1000L
        if (kotlin.math.abs(nowSec - ts) > 45L) return
        playRadioBeep(ToneGenerator.TONE_PROP_ACK)
        _pttState.value = _pttState.value.copy(isReceiving = true)
        VoiceNoteManager.togglePlayBase64(context, uid, p[2])
        mainHandler.postDelayed({ _pttState.value = _pttState.value.copy(isReceiving = false) }, 3500L)
    }

    fun openLivePttChannel(context: Context, myId: String, peerId: String) {
        _pttState.value = LivePttState(isChannelOpen = true, peerId = peerId)
    }

    fun closeLivePttChannel() {
        if (_pttState.value.isTransmitting) VoiceNoteManager.cancelRecording()
        _pttState.value = LivePttState()
    }
}