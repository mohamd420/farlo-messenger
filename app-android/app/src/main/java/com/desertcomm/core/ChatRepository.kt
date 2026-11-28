package com.desertcomm.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.desertcomm.BleManager
import com.desertcomm.BleState
import com.desertcomm.MainActivity
import com.desertcomm.modem.SatelliteNtnManager
import com.desertcomm.modem.SubNoiseAudioModem
import com.desertcomm.p2p.WifiDirectManager
import com.desertcomm.p2p.OnlineBlindRelay
import com.desertcomm.p2p.WispLanMesh
import com.desertcomm.security.HardwareKeystore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

enum class SendTransportMode(val label: String) {
    LOCAL_DIRECT("قريب"),
    SATELLITE_NTN("فضائي"),
    TACTICAL_RADIO("تكتيكي"),
    SUB_NOISE_8FSK("تحت الضجيج")
}

data class ChatMessage(
    val text: String,
    val isMe: Boolean,
    val timeText: String,
    val statusBadge: String = "",
    val uid: String = UUID.randomUUID().toString().take(8)
)

object ChatRepository {
    private const val MSG_CHANNEL_ID = "desertcomm_messages_high"

    private fun verifyAndPinSenderKeys(
        appContext: Context,
        sender: String,
        senderPubB64: String,
        sigB64: String?,
        canonical: ByteArray
    ): Boolean {
        if (sigB64 == null) {
            BleManager.setStatus(com.desertcomm.ui.SadaStrings.tr("⚠️ تم رفض رسالة غير موقّعة من $sender", "⚠️ Rejected unsigned message from $sender"))
            return false
        }
        val sb = runCatching { Base64.decode(sigB64, Base64.NO_WRAP) }.getOrNull()
        if (sb == null || sb.size != 96 || !NativeBridgeV3.verifySenderPayload(sb, canonical)) {
            BleManager.setStatus(com.desertcomm.ui.SadaStrings.tr("⚠️ تم رفض رسالة مزوّرة التوقيع من $sender", "⚠️ Rejected forged signature from $sender"))
            return false
        }
        val pk = runCatching { Base64.decode(senderPubB64, Base64.NO_WRAP) }.getOrNull()
        if (pk == null || pk.size != 32) {
            BleManager.setStatus(com.desertcomm.ui.SadaStrings.tr("⚠️ تم رفض مفتاح غير صالح من $sender", "⚠️ Rejected invalid public key from $sender"))
            return false
        }
        if (sender.startsWith("DC-") && HardwareKeystore.deriveIdFromPubKey(pk) != sender) { BleManager.setStatus(com.desertcomm.ui.SadaStrings.tr("⚠️ تم رفض هوية مزوّرة: $sender", "⚠️ Rejected spoofed ID: $sender")); return false }
        return synchronized(HardwareKeystore) {
        val existingX25519 = HardwareKeystore.getPeerPublicKey(appContext, sender)
        if (existingX25519 != null && !existingX25519.contentEquals(pk)) {
            BleManager.setStatus(com.desertcomm.ui.SadaStrings.tr("⚠️ تحذير أمني: تغيّر مفتاح $sender — تحقق من رقم الأمان!", "⚠️ Security Alert: $sender key changed — verify Safety Number!"))
            return@synchronized false
        }
        val edPrefs = appContext.getSharedPreferences("farlo_ed_pins", Context.MODE_PRIVATE)
        val vkB64 = Base64.encodeToString(sb.copyOfRange(0, 32), Base64.NO_WRAP)
        val pinnedEd = edPrefs.getString(sender, null)
        if (pinnedEd != null && pinnedEd != vkB64) {
            BleManager.setStatus(com.desertcomm.ui.SadaStrings.tr("⚠️ تحذير: تغيّر مفتاح توقيع $sender!", "⚠️ Alert: $sender signing key mismatch!"))
            return@synchronized false
        }
        if (existingX25519 == null) HardwareKeystore.savePeerPublicKey(appContext, sender, pk)
        if (pinnedEd == null) edPrefs.edit().putString(sender, vkB64).commit()
        true
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cryptoExecutor = Executors.newFixedThreadPool(4)
    private val mediaExecutor = java.util.concurrent.ThreadPoolExecutor(2, 2, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.ArrayBlockingQueue(8), java.util.concurrent.ThreadPoolExecutor.DiscardPolicy())
    private val diskExecutor = Executors.newSingleThreadExecutor()
    private val dtnScheduler = Executors.newSingleThreadScheduledExecutor()

    private val _myId = MutableStateFlow("جاري التوليد...")
    val myId: StateFlow<String> = _myId.asStateFlow()

    private val _conversations = MutableStateFlow(mapOf("" to emptyList<ChatMessage>()).filterKeys { false })
    val conversations = _conversations.asStateFlow()

    private val _savedPeers = MutableStateFlow<List<String>>(emptyList())
    val savedPeers: StateFlow<List<String>> = _savedPeers.asStateFlow()

    private val _nicknames = MutableStateFlow(mapOf("" to "").filterKeys { false })
    val nicknames = _nicknames.asStateFlow()

    private val _groups = MutableStateFlow(mapOf("" to "").filterKeys { false })
    val groups = _groups.asStateFlow()

    private val _blockedPeers = MutableStateFlow<Set<String>>(emptySet())
    val blockedPeers = _blockedPeers.asStateFlow()
    private val _onlineEnabled = MutableStateFlow(true)
    val onlineEnabled = _onlineEnabled.asStateFlow()

    private val _unreadCounts = MutableStateFlow(mapOf("" to 0).filterKeys { false })
    val unreadCounts = _unreadCounts.asStateFlow()

    @Volatile var currentOpenChatId: String? = null

    private val _transferProgress = MutableStateFlow(mapOf("" to 0f).filterKeys { false })
    val transferProgress = _transferProgress.asStateFlow()
    private val _reactions = MutableStateFlow(emptyMap<String, String>())
    val reactions = _reactions.asStateFlow()
    private val _chatLastActivity = MutableStateFlow(emptyMap<String, Long>())
    val chatLastActivity = _chatLastActivity.asStateFlow()

    fun sendReaction(appContext: Context, chatId: String, targetUid: String, emoji: String, sendMode: SendTransportMode) {
        val updated = _reactions.value.toMutableMap()
        updated[targetUid] = emoji
        _reactions.value = updated
        sendMessage(appContext, chatId, "REACT|$targetUid|$emoji", sendMode)
    }

    private fun scheduleDeliveryRetry(chatId: String, msgUid: String, destTag: String, dtnEnvelope: String) {
        listOf(4_000L, 12_000L, 30_000L, 60_000L).forEach { waitMs ->
            mainHandler.postDelayed({
                val stillUnacked = _conversations.value[chatId]?.firstOrNull { it.uid == msgUid }?.statusBadge != "✓✓"
                if (stillUnacked && _onlineEnabled.value) {
                    OnlineBlindRelay.ensureConnected()
                    OnlineBlindRelay.publishEnvelope(destTag, dtnEnvelope, null)
                }
            }, waitMs)
        }
    }

    private fun scheduleAutoDeleteIfExpiring(chatId: String, msgUid: String, text: String) {
        if (text.startsWith("EXP|")) {
            val sec = text.split("|", limit = 3).getOrNull(1)?.toIntOrNull() ?: return
            if (sec in 5..3600) {
                mainHandler.postDelayed({ applyRemoteDelete(chatId, msgUid) }, sec * 1000L)
            }
        }
    }

    private val _dtnQueuedCount = MutableStateFlow(0)
    val dtnQueuedCount: StateFlow<Int> = _dtnQueuedCount.asStateFlow()

    private val _keystoreReady = MutableStateFlow(false)
    val keystoreReady: StateFlow<Boolean> = _keystoreReady.asStateFlow()

    @Volatile var sessionKey: ByteArray? = null
        private set

    @Volatile private var engineStarted = false
    @Volatile var isUiInForeground = false

    fun onUiVisibilityChanged(inForeground: Boolean) {
        isUiInForeground = inForeground
        BleManager.setPowerMode(inForeground)
        WispLanMesh.setForegroundPower(inForeground)
    }
    private var appCtxRef: Context? = null
    @Volatile private var myStaticPriv: ByteArray? = null
    @Volatile private var myStaticPub: ByteArray? = null

    fun getShareableContactCode(): String {
        val pub = myStaticPub ?: return _myId.value
        return _myId.value + ":" + Base64.encodeToString(pub, Base64.NO_WRAP)
    }

    private val pendingMessages = mutableMapOf<Int, MutableList<ByteArray>>()
    private val completedMsgIds = LinkedHashSet<Int>()
    private fun padEnvelopePayload(s: String): String {
        val buckets = intArrayOf(256, 512, 1024, 2048)
        val target = buckets.firstOrNull { it > s.length + 12 } ?: return s
        val padLen = target - s.length - 12
        val pad = Base64.encodeToString(ByteArray((padLen * 3 / 4) + 3).also { SecureRandom().nextBytes(it) }, Base64.NO_WRAP).take(padLen)
        return "PAD|${s.length}|$s$pad"
    }
    private fun unpadEnvelopePayload(s: String): String {
        if (!s.startsWith("PAD|")) return s
        val p = s.split("|", limit = 3)
        val len = p.getOrNull(1)?.toIntOrNull() ?: return s
        val rest = p.getOrNull(2) ?: return s
        return if (rest.length >= len) rest.substring(0, len) else s
    }

    private val pqActiveTags = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val pqOfferedPeers = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var myPqDk: ByteArray? = null
    @Volatile private var myPqEk: ByteArray? = null
    private fun ensurePqKeys() {
        if (myPqDk == null || myPqEk == null) {
            NativeBridgeV3.pqGenerateMlkem768Keypair()?.takeIf { it.size == 2 }?.let {
                myPqDk = it[0]; myPqEk = it[1]
            }
        }
    }

    private val deliveredDtnIds = LinkedHashSet<String>()
    private fun markDeliveredOnce(ctx: Context, uid: String): Boolean {
        synchronized(deliveredDtnIds) {
            if (deliveredDtnIds.contains(uid)) return false
            if (_conversations.value.values.any { list -> list.any { it.uid == uid } }) {
                deliveredDtnIds.add(uid); return false
            }
            val prefs = ctx.getSharedPreferences("farlo_dedup", Context.MODE_PRIVATE)
            val saved = prefs.getStringSet("uids", emptySet()) ?: emptySet()
            if (saved.contains(uid)) { deliveredDtnIds.add(uid); return false }
            deliveredDtnIds.add(uid)
            prefs.edit().putStringSet("uids", (saved.toList().takeLast(400) + uid).toSet()).apply()
            return true
        }
    }
    private val relayedDtnIds = LinkedHashSet<String>()
    private val sentToPeerBundles = mutableMapOf<String, MutableSet<String>>()
    private const val MAX_MESH_HOPS = 6

    private fun blindDestTag(id: String): String {
        val clean = id.trim().uppercase()
        if (clean == "*") return "*"
        val hash = MessageDigest.getInstance("SHA-256").digest("DC-DEST:$clean".toByteArray(Charsets.UTF_8))
        return hash.take(8).joinToString("") { "%02X".format(it) }
    }

    private fun nowTime(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    fun startEngine(appContext: Context) {
        if (engineStarted) return
        engineStarted = true

        appCtxRef = appContext.applicationContext
        createMessageNotificationChannel(appContext)
        loadHistoryFromDisk(appContext)
        _savedPeers.value = HardwareKeystore.getSavedPeerIds(appContext)
        _nicknames.value = HardwareKeystore.getAllNicknames(appContext)
        _blockedPeers.value = HardwareKeystore.getBlockedPeers(appContext)
        _groups.value = HardwareKeystore.getAllGroups(appContext)

        val (sPriv, sPub) = HardwareKeystore.getOrCreateStaticKeypair(appContext) {
            val pair = NativeBridgeV3.generateStaticKeypair() ?: error("Static keygen failed")
            pair[0] to pair[1]
        }
        myStaticPriv = sPriv
        myStaticPub = sPub
        val permanent = HardwareKeystore.deriveIdFromPubKey(sPub)
        _myId.value = permanent

        cryptoExecutor.execute {
            runCatching {
                val dtnKey = HardwareKeystore.getOrCreateDtnStorageKey(appContext)
                NativeBridgeV3.initDtnStore(dtnKey)
            }.onSuccess {
                _keystoreReady.value = true
            }.onFailure { e ->
                Log.e("DesertRepo", "Keystore/DTN init error: ${e.message}")
            }
        }

        BleManager.init(appContext)
        BleManager.onDisconnected = { sessionKey = null }

        WifiDirectManager.onFrameReceived = { frame ->
            cryptoExecutor.execute {
                handleIncoming(appContext, String(frame, Charsets.UTF_8))
            }
        }
        WifiDirectManager.onConnectedReady = {
            cryptoExecutor.execute {
                val relays = NativeBridgeV3.dtnListRelayBundles(System.currentTimeMillis()) ?: emptyArray()
                relays.forEach { WifiDirectManager.sendFrame(it) }
            }
        }
        WifiDirectManager.start(appContext)
        WispLanMesh.onPacketReceived = { pkt ->
            cryptoExecutor.execute { handleIncoming(appContext, pkt) }
        }
        WispLanMesh.start(appContext)
        _onlineEnabled.value = HardwareKeystore.isOnlineRelayEnabled(appContext)
        if (HardwareKeystore.isTorProxyEnabled(appContext)) { com.desertcomm.p2p.EmbeddedTorManager.start(appContext) { p -> OnlineBlindRelay.setTorEnabled(true, p) } } else { OnlineBlindRelay.setTorEnabled(false) }
        OnlineBlindRelay.onRelayConnected = { mainHandler.postDelayed({ cryptoExecutor.execute { flushDtnOverOnline(appContext) } }, 1_500L) }
        OnlineBlindRelay.onEnvelopeReceived = { env ->
            cryptoExecutor.execute { handleIncoming(appContext, env) }
        }
        refreshOnlineSubscriptions(appContext)
        BleManager.onAirBurstReceived = { pkt ->
            cryptoExecutor.execute { handleIncoming(appContext, pkt) }
        }

        BleManager.onReadyForHandshake = { isServer ->
            if (!isServer) {
                cryptoExecutor.execute {
                    BleManager.setStatus("1/4: توليد وإرسال مفتاح العميل...")
                    NativeBridge.safeGenerateSessionKey()
                        .onSuccess { pubKey -> BleManager.sendHandshake(pubKey) }
                        .onFailure { err -> BleManager.setStatus("فشل توليد المفتاح: ${err.message}") }
                }
            }
        }

        BleManager.onHandshakeReceived = { peerPubKey ->
            cryptoExecutor.execute {
                if (BleManager.isServer) {
                    BleManager.setStatus("2/4: استلم الخادم المفتاح، جاري الرد...")
                    val serverPub = NativeBridge.safeGenerateSessionKey().getOrElse { err ->
                        BleManager.setStatus("فشل مفتاح الخادم: ${err.message}")
                        return@execute
                    }
                    val shared = NativeBridge.safeComputeSharedKey(peerPubKey).getOrElse { err ->
                        BleManager.setStatus("فشل المفتاح المشترك: ${err.message}")
                        return@execute
                    }
                    BleManager.sendHandshake(serverPub)
                    sessionKey = shared
                    BleManager.markSecureConnected()
                    Thread.sleep(250)
                    flushDtnOverBle(appContext, shared)
                } else {
                    BleManager.setStatus("3/4: استلم العميل الرد، جاري حساب المفتاح...")
                    val shared = NativeBridge.safeComputeSharedKey(peerPubKey).getOrElse { err ->
                        BleManager.setStatus("فشل إتمام المصافحة: ${err.message}")
                        return@execute
                    }
                    sessionKey = shared
                    BleManager.markSecureConnected()
                    Thread.sleep(250)
                    flushDtnOverBle(appContext, shared)
                }
            }
        }

        BleManager.onDataReceived = { data ->
            val currentKey = sessionKey
            if (BleManager.isHandshakeCompleted && currentKey != null && data.size > 4) {
                cryptoExecutor.execute {
                    val msgId = ByteBuffer.wrap(data, 0, 4).int
                    val shard = data.copyOfRange(4, data.size)
                    synchronized(pendingMessages) {
                        if (completedMsgIds.contains(msgId)) return@execute
                        val list = pendingMessages.getOrPut(msgId) { mutableListOf() }
                        list.add(shard)
                        if (list.size >= 10) {
                            val attempt = NativeBridge.safeOpen(currentKey, list.toList())
                            if (attempt.isSuccess) {
                                val rawText = String(attempt.getOrThrow(), Charsets.UTF_8)
                                pendingMessages.remove(msgId)
                                completedMsgIds.add(msgId)
                                if (completedMsgIds.size > 200) completedMsgIds.remove(completedMsgIds.first())
                                handleIncoming(appContext, rawText)
                            } else if (list.size >= 14) {
                                pendingMessages.remove(msgId)
                                BleManager.setStatus("تعذر فك تشفير الرسالة")
                            }
                        }
                    }
                }
            }
        }

        BleManager.startDiscoveryAndServer(appContext, permanent)
        dtnScheduler.scheduleWithFixedDelay({
            runCatching {
                if (_dtnQueuedCount.value <= 0) return@runCatching
                if (BleManager.bleState.value != BleState.ADVERTISING_AND_SCANNING) return@runCatching
                if (WifiDirectManager.isConnected.value) return@runCatching

                val bundles = NativeBridgeV3.dtnListRelayBundles(System.currentTimeMillis()) ?: emptyArray()
                if (bundles.isEmpty()) {
                    _dtnQueuedCount.value = 0
                    return@runCatching
                }

                val peersList = BleManager.peers.value
                if (peersList.isEmpty()) return@runCatching

                // 1) إذا كان المستقبل نفسه قريباً -> نتصل به مباشرة بدون أي وسيط
                val directTarget = peersList.firstOrNull { peer ->
                    val peerTag = blindDestTag(peer.id)
                    bundles.any { b ->
                        val p = String(b, Charsets.UTF_8).split("|", limit = 5)
                        val uid = p.getOrNull(1) ?: return@any false
                        val destTag = p.getOrNull(2) ?: ""
                        destTag == peerTag && !(sentToPeerBundles[peer.id]?.contains(uid) ?: false)
                    }
                }
                if (directTarget != null) {
                    BleManager.connectToDevice(appContext, directTarget.device)
                    return@runCatching
                }

                // 2) المستقبل بعيد -> نختار وسيطاً قريباً لم يستلم هذه الحزم بعد للتمرير الأعمى الصامت
                val relayCandidate = peersList.firstOrNull { peer ->
                    bundles.any { b ->
                        val uid = String(b, Charsets.UTF_8).split("|", limit = 3).getOrNull(1) ?: return@any false
                        !(sentToPeerBundles[peer.id]?.contains(uid) ?: false)
                    }
                } ?: return@runCatching

                BleManager.connectToDevice(appContext, relayCandidate.device)
            }
        }, 15, 15, TimeUnit.SECONDS)
    }

    private fun handleIncoming(appContext: Context, rawText: String) {
        val localId = _myId.value.uppercase()
        if (rawText.startsWith("DTN|")) {
            val parts = rawText.split("|", limit = 5)
            if (parts.size == 5) {
                val bundleUid = parts[1]
                val destTag = parts[2]
                val hops = parts[3].toIntOrNull() ?: 0
                val rawBody = parts[4]
                val myTag = blindDestTag(localId)

                val matchedGroupId = _groups.value.keys.firstOrNull { blindDestTag(it) == destTag }
                if (matchedGroupId != null && rawBody.startsWith("GRP:")) {
                    if (true) {
                        val gKey = HardwareKeystore.getGroupKey(appContext, matchedGroupId)
                        if (gKey != null) {
                            val dec = runCatching {
                                val ct = Base64.decode(rawBody.removePrefix("GRP:"), Base64.NO_WRAP)
                                val pt = NativeBridgeV3.openDtnBody(gKey, ct)!!
                                String(pt, Charsets.UTF_8)
                            }.getOrNull()
                            if (dec != null && dec.startsWith("MSG|")) {
                                val mp = dec.split("|", limit = 4)
                                if (mp.size == 4) {
                                    val sender = mp[1].uppercase()
                                    if (sender != localId && !_blockedPeers.value.contains(sender)) {
                                        val gp = mp[2].split(".", limit = 2); val gCanon = "MSG|$sender|${gp[0]}|${mp[3]}".toByteArray(Charsets.UTF_8); if (!verifyAndPinSenderKeys(appContext, sender, gp[0], gp.getOrNull(1), gCanon)) return; if (!markDeliveredOnce(appContext, bundleUid)) return
                                        val body = mp[3]
                                        val senderLabel = _nicknames.value[sender] ?: sender
                                        if (body.startsWith("PTT_LIVE|")) { mainHandler.post { com.desertcomm.p2p.SadaCallManager.onIncomingPttLive(appContext, bundleUid, body) }; return }
                            val displayBody = if (body.startsWith("VOICE:") || body.startsWith("IMG:")) body else "$senderLabel:\n$body"
                                        appendMessage(matchedGroupId, ChatMessage(displayBody, isMe = false, timeText = nowTime(), statusBadge = if (hops > 0) "↻$hops" else "", uid = bundleUid))
                                        val gName = _groups.value[matchedGroupId] ?: matchedGroupId
                                        BleManager.setStatus("رسالة جديدة في مجموعة $gName")
                                        if (!isUiInForeground && !HardwareKeystore.isMuted(appContext)) {
                                            showIncomingNotification(appContext, matchedGroupId, gName, "$senderLabel: " + when {
                                                body.startsWith("VOICE:") -> "🎤 رسالة صوتية"
                                                body.startsWith("IMG:") -> "📷 صورة"
                                                else -> body
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // استمرار تمرير رسالة المجموعة لباقي الأعضاء
                    if (hops < MAX_MESH_HOPS && relayedDtnIds.add(bundleUid)) {
                        val nextEnvelope = "DTN|$bundleUid|$destTag|${hops + 1}|$rawBody"
                        WispLanMesh.broadcastDtnEnvelope(nextEnvelope)
                    if (_onlineEnabled.value) OnlineBlindRelay.publishEnvelope(destTag, nextEnvelope)
                        NativeBridgeV3.dtnEnqueueBundle(nextEnvelope.toByteArray(Charsets.UTF_8), System.currentTimeMillis(), 86_400_000L)
                        val all = NativeBridgeV3.dtnListRelayBundles(System.currentTimeMillis()) ?: emptyArray()
                        HardwareKeystore.persistDtnBundles(appContext, all.toList())
                        _dtnQueuedCount.value = all.size
                    }
                    return
                }
                if (destTag == myTag || destTag == "*") {
                    if (true) {
                        val decryptedInner = when {
                            rawBody.startsWith("BOX:") -> {
                                val priv = myStaticPriv
                                if (priv != null) {
                                    runCatching {
                                        val box = Base64.decode(rawBody.removePrefix("BOX:"), Base64.NO_WRAP)
                                        val pt = NativeBridgeV3.openFromSender(priv, box)!!
                                        String(pt, Charsets.UTF_8)
                                    }.getOrNull()
                                } else null
                            }
                            rawBody.startsWith("PQR:") -> {
                                runCatching {
                                    val pqrParts = rawBody.removePrefix("PQR:").split(":", limit = 2)
                                    if (pqrParts.size == 2) {
                                        val pkt = Base64.decode(pqrParts[1], Base64.NO_WRAP)
                                        val pt = NativeBridgeV3.pqRatchetDecrypt(pqrParts[0], pkt) ?: run { if (!NativeBridgeV3.pqHasSession(pqrParts[0])) { _savedPeers.value.firstOrNull { blindDestTag(it) == pqrParts[0] }?.let { pid -> if (pqOfferedPeers.add(pid)) { ensurePqKeys(); myPqEk?.let { ek -> sendControlEnvelope(appContext, pid, "PQ_OFFER|" + Base64.encodeToString(ek, Base64.NO_WRAP), SendTransportMode.LOCAL_DIRECT) } } } }; return@runCatching null }; NativeBridgeV3.pqExportSession(pqrParts[0])?.let { st -> diskExecutor.execute { HardwareKeystore.savePqSessionState(appContext, pqrParts[0], st) } }
                                        String(pt, Charsets.UTF_8)
                                    } else null
                                }.getOrNull()
                            }
                            rawBody.startsWith("ENC:") -> null
                            else -> null
                        }
                        val unpaddedInner = decryptedInner?.let { unpadEnvelopePayload(it) }; if (unpaddedInner != null && unpaddedInner.startsWith("MSG|")) {
                            val mParts = unpaddedInner.split("|", limit = 4)
                            if (mParts.size == 4) {
                                val sender = mParts[1].uppercase()
                                if (sender == localId) return
                                val pubParts = mParts[2].split(".", limit = 2)
                                val canonical = "MSG|$sender|${pubParts[0]}|${mParts[3]}".toByteArray(Charsets.UTF_8)
                                if (!verifyAndPinSenderKeys(appContext, sender, pubParts[0], pubParts.getOrNull(1), canonical)) return
                                val body = mParts[3]
                                if (_blockedPeers.value.contains(sender)) return; if (!markDeliveredOnce(appContext, bundleUid)) return
                                if (body == "PQ_RESET") { pqActiveTags.remove(blindDestTag(sender)); pqOfferedPeers.remove(sender); appendMessage(sender, ChatMessage(com.desertcomm.ui.SadaStrings.tr("⚠️ تم إعادة ضبط جلسة التشفير الكمي — تحقق من رقم الأمان", "⚠️ PQ session reset — verify Safety Number"), isMe = false, timeText = nowTime())); return }
                                if (body.startsWith("PQ_OFFER|")) {
                                    val peerEk = runCatching { Base64.decode(body.removePrefix("PQ_OFFER|").trim(), Base64.NO_WRAP) }.getOrNull()
                                    val myPriv = myStaticPriv
                                    val peerPub = HardwareKeystore.getPeerPublicKey(appContext, sender)
                                    if (peerEk != null && myPriv != null && peerPub != null) {
                                        val ct = NativeBridgeV3.pqInitiateSession(blindDestTag(sender), peerEk, myPriv, peerPub, peerPub)
                                        if (ct != null) {
                                            pqActiveTags.add(blindDestTag(sender)); NativeBridgeV3.pqExportSession(blindDestTag(sender))?.let { st -> diskExecutor.execute { HardwareKeystore.savePqSessionState(appContext, blindDestTag(sender), st) } }
                                            sendControlEnvelope(appContext, sender, "PQ_ACCEPT|" + Base64.encodeToString(ct, Base64.NO_WRAP), SendTransportMode.LOCAL_DIRECT)
                                        }
                                    }
                                    return
                                }
                                if (body.startsWith("PQ_ACCEPT|")) {
                                    val ct = runCatching { Base64.decode(body.removePrefix("PQ_ACCEPT|").trim(), Base64.NO_WRAP) }.getOrNull()
                                    val myPriv = myStaticPriv
                                    val dk = myPqDk
                                    val peerPub = HardwareKeystore.getPeerPublicKey(appContext, sender)
                                    if (ct != null && myPriv != null && dk != null && peerPub != null) {
                                        if (NativeBridgeV3.pqAcceptSession(blindDestTag(sender), dk, ct, myPriv, peerPub, myPriv)) {
                                            pqActiveTags.add(blindDestTag(sender)); NativeBridgeV3.pqExportSession(blindDestTag(sender))?.let { st -> diskExecutor.execute { HardwareKeystore.savePqSessionState(appContext, blindDestTag(sender), st) } }
                                        }
                                    }
                                    return
                                }
                                if (body.startsWith("EDIT|")) {
                                    val ep = body.split("|", limit = 3)
                                    if (ep.size == 3) {
                                        applyRemoteEdit(sender, ep[1], ep[2])
                                    }
                                    return
                                }
                                if (body.startsWith("ACK|")) {
                                    val ackUid = body.removePrefix("ACK|").trim()
                                    applyDeliveryAck(sender, ackUid)
                                    return
                                }
                                if (body.startsWith("WEBRTC_OFFER|") || body.startsWith("WEBRTC_ANSWER|") || body.startsWith("WEBRTC_ICE|") || body.startsWith("WEBRTC_END|")) {
                        val callerName = _nicknames.value[sender] ?: sender
                        mainHandler.post { com.desertcomm.p2p.SadaCallManager.onIncomingSignal(appContext, sender, callerName, body) }
                        return
                    }
                    if (body.startsWith("PTT_LIVE|")) {
                        mainHandler.post { com.desertcomm.p2p.SadaCallManager.onIncomingPttLive(appContext, bundleUid, body) }
                        return
                    }
                    if (body.startsWith("REACT|")) {
                        val rp = body.split("|", limit = 3)
                        if (rp.size == 3) {
                            val updated = _reactions.value.toMutableMap()
                            updated[rp[1]] = rp[2]
                            _reactions.value = updated
                        }
                        return
                    }
                    if (body.startsWith("HDIMG|") || body.startsWith("HDVOICE|")) {
                        val mp = body.split("|")
                        val declBytes = mp.getOrNull(2)?.toLongOrNull() ?: Long.MAX_VALUE
                        if (mp.size >= 5 && declBytes in 1..(8L * 1024L * 1024L) && mediaExecutor.activeCount < 2 && mediaExecutor.queue.size < 4) {
                            downloadMediaForMessage(appContext, bundleUid, mp[1], mp[3], mp[4], maxBytes = 8L * 1024L * 1024L) {}
                        }
                    }
                    if (body.startsWith("EXP|")) {
                        scheduleAutoDeleteIfExpiring(sender, bundleUid, body)
                    }
                    if (body.startsWith("DEL|")) {
                                    val delUid = body.removePrefix("DEL|").trim()
                                    applyRemoteDelete(sender, delUid)
                                    return
                                }
                                savePeer(appContext, sender)
                                val hopTag = if (hops > 0) "↻$hops" else ""
                                appendMessage(sender, ChatMessage(body, isMe = false, timeText = nowTime(), statusBadge = hopTag, uid = bundleUid))
                                sendControlEnvelope(appContext, sender, "ACK|$bundleUid", SendTransportMode.LOCAL_DIRECT)
                                val senderLabel = _nicknames.value[sender] ?: sender
                                BleManager.setStatus("رسالة جديدة من $senderLabel")
                                if (!isUiInForeground && !HardwareKeystore.isMuted(appContext)) {
                                    showIncomingNotification(appContext, sender, senderLabel, when { body.startsWith("PTT:") -> "📻 بث لاسلكي فوري"; body.startsWith("VOICE:") -> "🎤 رسالة صوتية"; body.startsWith("IMG:") -> "📷 صورة مشفرة"; body.startsWith("LOC:") -> "📍 موقع جغرافي"; else -> body })
                                }
                            }
                        }
                    }
                } else if (hops < MAX_MESH_HOPS && relayedDtnIds.add(bundleUid)) {
                    // وسيط أعمى وصامت 100%: لا يعلم المرسل ولا المستلم ولا النص ولا يظهر أي شيء على شاشته
                    val nextEnvelope = "DTN|$bundleUid|$destTag|${hops + 1}|$rawBody"
                    WispLanMesh.broadcastDtnEnvelope(nextEnvelope)
                    if (_onlineEnabled.value) OnlineBlindRelay.publishEnvelope(destTag, nextEnvelope)
                    NativeBridgeV3.dtnEnqueueBundle(nextEnvelope.toByteArray(Charsets.UTF_8), System.currentTimeMillis(), 86_400_000L)
                    val all = NativeBridgeV3.dtnListRelayBundles(System.currentTimeMillis()) ?: emptyArray()
                    HardwareKeystore.persistDtnBundles(appContext, all.toList())
                    _dtnQueuedCount.value = all.size
                }
                return
            }
        }
        val fallback = _savedPeers.value.firstOrNull() ?: "عام"
        appendMessage(fallback, ChatMessage(rawText, isMe = false, timeText = nowTime()))
        if (!isUiInForeground && !HardwareKeystore.isMuted(appContext)) {
            showIncomingNotification(appContext, fallback, fallback, rawText)
        }
    }

    private fun flushDtnOverOnline(appContext: Context) {
        if (!_onlineEnabled.value || !OnlineBlindRelay.isOnlineConnected.value) return
        val relays = NativeBridgeV3.dtnListRelayBundles(System.currentTimeMillis()) ?: return
        if (relays.isEmpty()) return
        var sentAny = false
        relays.forEach { bundleBytes ->
            val env = String(bundleBytes, Charsets.UTF_8)
            val p = env.split("|", limit = 5)
            val uid = p.getOrNull(1).orEmpty()
            val destTag = p.getOrNull(2).orEmpty()
            if (uid.isNotEmpty() && destTag.isNotEmpty()) {
                OnlineBlindRelay.publishEnvelope(destTag, env) { ok ->
                    if (ok) {
                        _conversations.value.keys.forEach { cid -> updateMessageBadgeIfPending(cid, uid, "✓") }
                    }
                }
                sentAny = true
            }
        }
        if (sentAny) {
            mainHandler.postDelayed({
                cryptoExecutor.execute {
                    NativeBridgeV3.dtnClearStore()
                    diskExecutor.execute { HardwareKeystore.persistDtnBundles(appContext, emptyList()) }
                    _dtnQueuedCount.value = 0
                }
            }, 3_000L)
        }
    }

    private fun flushDtnOverBle(appContext: Context, key: ByteArray) {
        val relays = NativeBridgeV3.dtnListRelayBundles(System.currentTimeMillis()) ?: return
        if (relays.isEmpty()) return
        val peerId = BleManager.connectedPeerId ?: "UNKNOWN_PEER"
        val peerTag = blindDestTag(peerId)
        val alreadySent = sentToPeerBundles.getOrPut(peerId) { mutableSetOf() }

        var sentNow = 0
        val remaining = mutableListOf<ByteArray>()

        relays.forEach { bundleBytes ->
            val p = String(bundleBytes, Charsets.UTF_8).split("|", limit = 5)
            val uid = p.getOrNull(1) ?: ""
            val destTag = p.getOrNull(2) ?: ""

            if (uid.isNotEmpty() && !alreadySent.contains(uid)) {
                NativeBridge.safeSeal(key, bundleBytes).onSuccess { packets ->
                    val msgId = SecureRandom().nextInt()
                    val framed = packets.map { pkt ->
                        ByteBuffer.allocate(4 + pkt.size).putInt(msgId).put(pkt).array()
                    }
                    BleManager.sendPackets(framed)
                    alreadySent.add(uid)
                    sentNow++
                }
            }
            // نحذف الحزمة فقط إذا كان هذا الهاتف هو المستهدف المباشر؛ أما لو كان وسيطاً فنحتفظ بها لهواتف أخرى
            if (destTag != peerTag) {
                remaining.add(bundleBytes)
            }
        }

        NativeBridgeV3.dtnClearStore()
        remaining.forEach { NativeBridgeV3.dtnEnqueueBundle(it, System.currentTimeMillis(), 86_400_000L) }
        HardwareKeystore.persistDtnBundles(appContext, remaining)
        _dtnQueuedCount.value = remaining.size

        // إذا كان التمرير في الخلفية عبر وسيط، نغلق القناة بصمت بعد ثانيتين لمواصلة البحث
        if (sentNow > 0 && !isUiInForeground) {
            mainHandler.postDelayed({ BleManager.disconnectSilentForNextHop() }, 2_000L)
        }
    }

    private fun setProgress(uid: String, progress: Float?) {
        mainHandler.post {
            val map = _transferProgress.value.toMutableMap()
            if (progress == null || progress >= 1.0f) map.remove(uid) else map[uid] = progress
            _transferProgress.value = map
        }
    }

    fun sendOnlineEncryptedMedia(
        appContext: Context,
        chatId: String,
        kindPrefix: String, // "HDIMG" | "VIDEO" | "FILE" | "HDVOICE"
        rawBytes: ByteArray,
        fileName: String,
        sendMode: SendTransportMode
    ) {
        if (!(_onlineEnabled.value && OnlineBlindRelay.isOnlineConnected.value)) {
            BleManager.setStatus("إرسال الملفات والفيديو والوسائط عالية الدقة متاح في وضع الإنترنت فقط")
            return
        }
        val uid = UUID.randomUUID().toString().take(8)
        val cleanName = fileName.replace("|", "_")
        val tempKey = "LOCAL_$uid"
        runCatching { EncryptedMediaTransfer.stableCacheFile(appContext, tempKey, cleanName).writeBytes(rawBytes) }
        appendMessage(chatId, ChatMessage("$kindPrefix|$cleanName|${rawBytes.size}|local|$tempKey", isMe = true, timeText = nowTime(), statusBadge = "⏳", uid = uid))
        mediaExecutor.execute {
            EncryptedMediaTransfer.encryptAndUploadBytes(appContext, rawBytes, fileName) { _ ->
            }.onSuccess { res ->
                mainHandler.post {
                    val cur = _conversations.value.toMutableMap()
                    cur[chatId] = cur[chatId].orEmpty().filterNot { it.uid == uid }
                    _conversations.value = cur
                    val payload = "$kindPrefix|${res.fileName}|${res.sizeBytes}|${res.downloadUrl}|${res.keyB64}"
                    synchronized(pqActiveTags) { Thread.sleep(300); sendMessage(appContext, chatId, payload, sendMode) }
                }
            }.onFailure { err ->
                mainHandler.post {
                    val cur = _conversations.value.toMutableMap()
                    cur[chatId] = cur[chatId].orEmpty().map { if (it.uid == uid) it.copy(statusBadge = "⚠️") else it }
                    _conversations.value = cur
                }
                BleManager.setStatus("فشل رفع الملف: ${err.message}")
            }
        }
    }

    fun downloadMediaForMessage(
        appContext: Context,
        msgUid: String,
        fileName: String,
        url: String,
        keyB64: String,
        maxBytes: Long = 25L * 1024L * 1024L,
        onReady: (java.io.File) -> Unit
    ) {
        val cachedFile = EncryptedMediaTransfer.stableCacheFile(appContext, keyB64, fileName); if (cachedFile.exists() && cachedFile.length() > 0) { mainHandler.post { onReady(cachedFile) }; return }; setProgress(msgUid, 0.05f)
        mediaExecutor.execute {
            EncryptedMediaTransfer.downloadAndDecryptToFile(appContext, msgUid, url, keyB64, fileName, maxBytes) { p ->
                setProgress(msgUid, p)
            }.onSuccess { file ->
                setProgress(msgUid, null)
                mainHandler.post { onReady(file) }
            }.onFailure { err ->
                setProgress(msgUid, null)
                BleManager.setStatus("فشل تنزيل الملف: ${err.message}")
            }
        }
    }

    fun sendLocation(appContext: Context, chatId: String, lat: Double, lon: Double, sendMode: SendTransportMode) {
        val formatted = String.format(Locale.US, "LOC:%.5f,%.5f", lat, lon)
        sendMessage(appContext, chatId, formatted, sendMode)
    }

    fun sendImageBase64(appContext: Context, chatId: String, imgB64: String, sendMode: SendTransportMode) {
        sendMessage(appContext, chatId, "IMG:$imgB64", sendMode)
    }

    fun forwardMessage(appContext: Context, targetChatId: String, msgText: String, sendMode: SendTransportMode) {
        sendMessage(appContext, targetChatId, msgText, sendMode)
    }

    fun sendVoiceNote(appContext: Context, chatId: String, audioBytes: ByteArray, sendMode: SendTransportMode) {
        val b64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)
        sendMessage(appContext, chatId, "VOICE:$b64", sendMode)
    }

    fun sendMessage(appContext: Context, chatId: String, textToSend: String, sendMode: SendTransportMode) {
        val time = nowTime()
        val msgUid = UUID.randomUUID().toString().take(8)
        val initialBadge = when {
            sendMode == SendTransportMode.SUB_NOISE_8FSK -> "♪"
            sendMode == SendTransportMode.SATELLITE_NTN -> "🛰"
            WifiDirectManager.isConnected.value -> "✓"
            BleManager.bleState.value == BleState.CONNECTED_SECURE && sessionKey != null -> "✓"
            _onlineEnabled.value && OnlineBlindRelay.isOnlineConnected.value -> "✓"
            else -> "⏳"
        }
        // إظهار الرسالة على الشاشة فوراً (0ms) بدون أي تجميد للواجهة
        val isSilentSignal = textToSend.startsWith("WEBRTC_") || textToSend.startsWith("PTT_LIVE|") || textToSend.startsWith("CALL_") || textToSend.startsWith("REACT|")
        if (!isSilentSignal) {
            appendMessage(chatId, ChatMessage(textToSend, true, time, initialBadge, msgUid))
            scheduleAutoDeleteIfExpiring(chatId, msgUid, textToSend)
        }

        cryptoExecutor.execute {
            diskExecutor.execute { savePeer(appContext, chatId) }
            val localId = _myId.value
            val myPubB64 = myStaticPub?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: ""
            val rawSignInput = "MSG|$localId|$myPubB64|$textToSend"; val sigB64 = myStaticPriv?.let { NativeBridgeV3.signSenderPayload(it, rawSignInput.toByteArray(Charsets.UTF_8)) }?.let { Base64.encodeToString(it, Base64.NO_WRAP) }; val pubField = if (sigB64 != null) "$myPubB64.$sigB64" else myPubB64; val innerPayload = "MSG|$localId|$pubField|$textToSend"
            val groupKey = if (chatId.startsWith("GRP-")) HardwareKeystore.getGroupKey(appContext, chatId) else null
            val recipientPub = if (groupKey == null) HardwareKeystore.getPeerPublicKey(appContext, chatId) else null
            val encryptedBody = when {
                groupKey != null -> runCatching {
                    val ct = NativeBridgeV3.sealDtnBody(groupKey, innerPayload.toByteArray(Charsets.UTF_8))!!
                    "GRP:" + Base64.encodeToString(ct, Base64.NO_WRAP)
                }.getOrElse { BleManager.setStatus(com.desertcomm.ui.SadaStrings.tr("فشل التشفير — تم إيقاف الإرسال لحمايتك", "Encryption failed — send aborted")); return@execute }
                recipientPub != null && pqActiveTags.contains(blindDestTag(chatId)) -> runCatching { val pkt = synchronized(pqActiveTags) { val p = NativeBridgeV3.pqRatchetEncrypt(blindDestTag(chatId), padEnvelopePayload(innerPayload).toByteArray(Charsets.UTF_8))!!; NativeBridgeV3.pqExportSession(blindDestTag(chatId))?.let { st -> diskExecutor.execute { HardwareKeystore.savePqSessionState(appContext, blindDestTag(chatId), st) } }; p }; "PQR:${blindDestTag(localId)}:" + Base64.encodeToString(pkt, Base64.NO_WRAP) }.getOrElse { pqActiveTags.remove(blindDestTag(chatId)); pqOfferedPeers.remove(chatId); BleManager.setStatus(com.desertcomm.ui.SadaStrings.tr("⚠️ تعذّر تشفير جلسة PQ — أعد المحاولة لإعادة المزامنة", "⚠️ PQ session encrypt failed — retry to resync")); return@execute }
                recipientPub != null -> runCatching { if (OnlineBlindRelay.isOnlineConnected.value && !textToSend.startsWith("PQ_") && pqOfferedPeers.add(chatId)) { ensurePqKeys(); myPqEk?.let { ek -> sendControlEnvelope(appContext, chatId, "PQ_OFFER|" + Base64.encodeToString(ek, Base64.NO_WRAP), sendMode) } }
                    val box = NativeBridgeV3.sealToRecipient(recipientPub, padEnvelopePayload(innerPayload).toByteArray(Charsets.UTF_8))!!
                    "BOX:" + Base64.encodeToString(box, Base64.NO_WRAP)
                }.getOrElse { BleManager.setStatus(com.desertcomm.ui.SadaStrings.tr("فشل التشفير — تم إيقاف الإرسال لحمايتك", "Encryption failed — send aborted")); return@execute }
                else -> {
                    BleManager.setStatus(com.desertcomm.ui.SadaStrings.tr("يرجى مسح QR أو لصق الكود الكامل للجهة قبل الإرسال (ممنوع التشفير بدون مفتاح المستلم)", "Scan QR or paste contact's full invite code first (recipient key required)"))
                    return@execute
                }
            }
            val destTag = blindDestTag(chatId)
            val dtnEnvelope = "DTN|$msgUid|$destTag|0|$encryptedBody"
            val key = sessionKey
            val isSecure = BleManager.bleState.value == BleState.CONNECTED_SECURE && key != null
            val wfdConnected = WifiDirectManager.isConnected.value

            when (sendMode) {
                SendTransportMode.LOCAL_DIRECT -> {
                    relayedDtnIds.add(msgUid)
                    fun enqueueAndTriggerBle() {
                        val payload = dtnEnvelope.toByteArray(Charsets.UTF_8)
                        NativeBridgeV3.dtnEnqueueBundle(payload, System.currentTimeMillis(), 86_400_000L)
                        val all = NativeBridgeV3.dtnListRelayBundles(System.currentTimeMillis()) ?: emptyArray()
                        diskExecutor.execute { HardwareKeystore.persistDtnBundles(appContext, all.toList()) }
                        _dtnQueuedCount.value = all.size
                        // محاولة اتصال BLE فورية بالهدف أو أقرب وسيط بدون انتظار 15 ثانية
                        val peersNow = BleManager.peers.value; if (peersNow.isEmpty()) BleManager.startDiscoveryAndServer(appContext, localId)
                        val direct = peersNow.firstOrNull { it.id.equals(chatId, ignoreCase = true) } ?: peersNow.firstOrNull()
                        if (direct != null && BleManager.bleState.value != BleState.CONNECTING) {
                            BleManager.connectToDevice(appContext, direct.device)
                        }
                    }

                    WispLanMesh.broadcastDtnEnvelope(dtnEnvelope)
                    if (wfdConnected) {
                        WifiDirectManager.sendFrame(dtnEnvelope.toByteArray(Charsets.UTF_8))
                    }
                    val onlineReady = _onlineEnabled.value && OnlineBlindRelay.isOnlineConnected.value
                    if (!onlineReady && isSecure && key != null) {
                        NativeBridge.safeSeal(key, dtnEnvelope.toByteArray(Charsets.UTF_8)).onSuccess { packets ->
                            val mid = SecureRandom().nextInt()
                            val framed = packets.map { pkt -> ByteBuffer.allocate(4 + pkt.size).putInt(mid).put(pkt).array() }
                            BleManager.sendPackets(framed)
                        }
                    }
                    if (onlineReady) {
                        val needAckRetry = !textToSend.startsWith("WEBRTC_") && !textToSend.startsWith("PTT_LIVE|") && !textToSend.startsWith("ACK|") && !chatId.startsWith("GRP-")
                        if (needAckRetry) scheduleDeliveryRetry(chatId, msgUid, destTag, dtnEnvelope)
                        OnlineBlindRelay.publishEnvelope(destTag, dtnEnvelope) { ok ->
                            if (ok) {
                                updateMessageBadgeIfPending(chatId, msgUid, "✓")
                            } else {
                                cryptoExecutor.execute { enqueueAndTriggerBle() }
                            }
                        }
                    }
                    if (!wfdConnected && !(isSecure && key != null) && !onlineReady) {
                        enqueueAndTriggerBle()
                    }
                }
                SendTransportMode.SUB_NOISE_8FSK -> {
                    SubNoiseAudioModem.transmitTextOverAudio(dtnEnvelope.toByteArray(Charsets.UTF_8))
                        .onFailure { err -> BleManager.setStatus("فشل 8-FSK: ${err.message}") }
                }
                SendTransportMode.SATELLITE_NTN -> {
                    SatelliteNtnManager.sendEncryptedBurst(appContext, dtnEnvelope.toByteArray(Charsets.UTF_8))
                        .onSuccess { info -> BleManager.setStatus(info) }
                        .onFailure { err -> BleManager.setStatus(err.message ?: "Satellite غير متاح") }
                }
                SendTransportMode.TACTICAL_RADIO -> {
                    BleManager.setStatus("بانتظار ربط قطعة LoRa/VHF")
                }
            }
        }
    }

    fun refreshOnlineSubscriptions(context: Context) {
        val localId = _myId.value
        if (!localId.startsWith("DC-")) return
        val tags = mutableListOf(blindDestTag(localId))
        _groups.value.keys.forEach { tags.add(blindDestTag(it)) }
        OnlineBlindRelay.configureAndStart(
            HardwareKeystore.getRelayServerUrl(context),
            tags,
            _onlineEnabled.value
        )
    }

    fun setOnlineRelayEnabled(context: Context, enabled: Boolean) {
        _onlineEnabled.value = enabled
        HardwareKeystore.setOnlineRelayEnabled(context, enabled)
        refreshOnlineSubscriptions(context)
    }

    fun createGroup(context: Context, groupName: String): String {
        val gid = "GRP-" + UUID.randomUUID().toString().take(8).uppercase()
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        _groups.value = HardwareKeystore.saveGroup(context, gid, groupName, key)
        _nicknames.value = HardwareKeystore.setPeerNickname(context, gid, groupName)
        refreshOnlineSubscriptions(context)
        return gid
    }

    fun joinGroupFromCode(context: Context, rawCode: String): String? {
        if (!rawCode.startsWith("GRP|")) return null
        val p = rawCode.split("|", limit = 4)
        if (p.size != 4) return null
        val gid = p[1].trim().uppercase()
        val gName = p[2].trim()
        val key = runCatching { Base64.decode(p[3].trim(), Base64.NO_WRAP) }.getOrNull()?.takeIf { it.size == 32 } ?: return null
        _groups.value = HardwareKeystore.saveGroup(context, gid, gName, key)
        _nicknames.value = HardwareKeystore.setPeerNickname(context, gid, gName)
        refreshOnlineSubscriptions(context)
        return gid
    }

    fun getGroupInviteCode(context: Context, groupId: String): String? {
        val key = HardwareKeystore.getGroupKey(context, groupId) ?: return null
        val name = _groups.value[groupId.uppercase()] ?: _nicknames.value[groupId.uppercase()] ?: groupId
        return "GRP|${groupId.uppercase()}|$name|${Base64.encodeToString(key, Base64.NO_WRAP)}"
    }

    fun markChatRead(context: Context, chatId: String) {
        currentOpenChatId = chatId
        mainHandler.post {
            val map = _unreadCounts.value.toMutableMap()
            if (map.remove(chatId) != null) {
                _unreadCounts.value = map
            }
        }
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.cancel(chatId.hashCode())
        }
    }

    fun clearOpenChat() {
        currentOpenChatId = null
    }

    fun setBlocked(context: Context, peerId: String, blocked: Boolean) {
        _blockedPeers.value = HardwareKeystore.setPeerBlocked(context, peerId, blocked)
    }

    fun deleteConversation(context: Context, chatId: String) {
        mainHandler.post {
            val map = _conversations.value.toMutableMap()
            map.remove(chatId)
            _conversations.value = map
            _savedPeers.value = HardwareKeystore.removeSavedPeer(context, chatId)
            if (chatId.startsWith("GRP-")) _groups.value = HardwareKeystore.removeGroup(context, chatId)
            persistHistoryToDisk(context, map)
        }
    }

    fun editSentMessage(context: Context, chatId: String, msgUid: String, newText: String, sendMode: SendTransportMode) {
        val clean = newText.trim()
        if (clean.isEmpty()) return
        mainHandler.post {
            val map = _conversations.value.toMutableMap()
            val list = map[chatId]?.map { m ->
                if (m.uid == msgUid) m.copy(text = clean, statusBadge = "معدّلة") else m
            } ?: return@post
            map[chatId] = list
            _conversations.value = map
            persistHistoryToDisk(context, map)
        }
        sendControlEnvelope(context, chatId, "EDIT|$msgUid|$clean", sendMode)
    }

    fun deleteMessage(context: Context, chatId: String, msgUid: String, sendMode: SendTransportMode, notifyPeer: Boolean) {
        mainHandler.post {
            val map = _conversations.value.toMutableMap()
            val list = map[chatId]?.filterNot { it.uid == msgUid } ?: return@post
            map[chatId] = list
            _conversations.value = map
            persistHistoryToDisk(context, map)
        }
        if (notifyPeer) {
            sendControlEnvelope(context, chatId, "DEL|$msgUid", sendMode)
        }
    }

    private fun applyRemoteEdit(chatId: String, msgUid: String, newText: String) {
        mainHandler.post {
            val map = _conversations.value.toMutableMap()
            val list = map[chatId]?.map { m ->
                if (m.uid == msgUid) m.copy(text = newText, statusBadge = "معدّلة") else m
            } ?: return@post
            map[chatId] = list
            _conversations.value = map
            appCtxRef?.let { persistHistoryToDisk(it, map) }
        }
    }

    private fun updateMessageBadgeIfPending(chatId: String, msgUid: String, badge: String) {
        mainHandler.post {
            val map = _conversations.value.toMutableMap()
            val list = map[chatId]?.map { m ->
                if (m.uid == msgUid && m.statusBadge == "⏳") m.copy(statusBadge = badge) else m
            } ?: return@post
            map[chatId] = list
            _conversations.value = map
        }
    }

    private fun applyDeliveryAck(chatId: String, msgUid: String) {
        mainHandler.post {
            val map = _conversations.value.toMutableMap()
            val list = map[chatId]?.map { m ->
                if (m.uid == msgUid) m.copy(statusBadge = "✓✓") else m
            } ?: return@post
            map[chatId] = list
            _conversations.value = map
            appCtxRef?.let { persistHistoryToDisk(it, map) }
        }
    }

    private fun applyRemoteDelete(chatId: String, msgUid: String) {
        mainHandler.post {
            val map = _conversations.value.toMutableMap()
            val list = map[chatId]?.filterNot { it.uid == msgUid } ?: return@post
            map[chatId] = list
            _conversations.value = map
            appCtxRef?.let { persistHistoryToDisk(it, map) }
        }
    }

    private fun sendControlEnvelope(appContext: Context, chatId: String, controlBody: String, sendMode: SendTransportMode) {
        val localId = _myId.value
        val myPubB64 = myStaticPub?.let { Base64.encodeToString(it, Base64.NO_WRAP) } ?: ""
        val rawCtrl = "MSG|$localId|$myPubB64|$controlBody"; val ctrlSig = myStaticPriv?.let { NativeBridgeV3.signSenderPayload(it, rawCtrl.toByteArray(Charsets.UTF_8)) }?.let { Base64.encodeToString(it, Base64.NO_WRAP) }; val ctrlPub = if (ctrlSig != null) "$myPubB64.$ctrlSig" else myPubB64; val innerPayload = "MSG|$localId|$ctrlPub|$controlBody"
        val groupKey = if (chatId.startsWith("GRP-")) HardwareKeystore.getGroupKey(appContext, chatId) else null
        val recipientPub = if (groupKey == null) HardwareKeystore.getPeerPublicKey(appContext, chatId) else null
        val encryptedBody = if (groupKey != null) {
            runCatching {
                val ct = NativeBridgeV3.sealDtnBody(groupKey, innerPayload.toByteArray(Charsets.UTF_8))!!
                "GRP:" + Base64.encodeToString(ct, Base64.NO_WRAP)
            }.getOrElse { BleManager.setStatus(com.desertcomm.ui.SadaStrings.tr("فشل التشفير — تم إيقاف الإرسال لحمايتك", "Encryption failed — send aborted")); return }
        } else if (recipientPub != null) {
            runCatching {
                val box = NativeBridgeV3.sealToRecipient(recipientPub, padEnvelopePayload(innerPayload).toByteArray(Charsets.UTF_8))!!
                "BOX:" + Base64.encodeToString(box, Base64.NO_WRAP)
            }.getOrElse { BleManager.setStatus(com.desertcomm.ui.SadaStrings.tr("فشل التشفير — تم إيقاف الإرسال لحمايتك", "Encryption failed — send aborted")); return }
        } else { return }
        val dtnEnvelope = "DTN|${UUID.randomUUID().toString().take(8)}|${blindDestTag(chatId)}|0|$encryptedBody"
        val key = sessionKey
        val isSecure = BleManager.bleState.value == BleState.CONNECTED_SECURE && key != null
        cryptoExecutor.execute {
            WispLanMesh.broadcastDtnEnvelope(dtnEnvelope)
            if (_onlineEnabled.value) OnlineBlindRelay.publishEnvelope(blindDestTag(chatId), dtnEnvelope)
            if (WifiDirectManager.isConnected.value) {
                WifiDirectManager.sendFrame(dtnEnvelope.toByteArray(Charsets.UTF_8))
            } else if (isSecure && key != null) {
                NativeBridge.safeSeal(key, dtnEnvelope.toByteArray(Charsets.UTF_8)).onSuccess { packets ->
                    val mid = SecureRandom().nextInt()
                    val framed = packets.map { pkt -> ByteBuffer.allocate(4 + pkt.size).putInt(mid).put(pkt).array() }
                    BleManager.sendPackets(framed)
                }
            } else {
                NativeBridgeV3.dtnEnqueueBundle(dtnEnvelope.toByteArray(Charsets.UTF_8), System.currentTimeMillis(), 86_400_000L)
                val all = NativeBridgeV3.dtnListRelayBundles(System.currentTimeMillis()) ?: emptyArray()
                HardwareKeystore.persistDtnBundles(appContext, all.toList())
                _dtnQueuedCount.value = all.size
            }
        }
    }

    fun savePeer(context: Context, peerId: String) {
        _savedPeers.value = HardwareKeystore.savePeerId(context, peerId)
    }

    fun setNickname(context: Context, peerId: String, nickname: String) {
        _nicknames.value = HardwareKeystore.setPeerNickname(context, peerId, nickname)
    }

    private fun appendMessage(chatId: String, msg: ChatMessage) {
        _chatLastActivity.value = _chatLastActivity.value + (chatId to System.currentTimeMillis())
        mainHandler.post {
            val map = _conversations.value.toMutableMap()
            map[chatId] = (map[chatId] ?: emptyList()) + msg
            if (!msg.isMe && (!isUiInForeground || currentOpenChatId != chatId)) {
                val uMap = _unreadCounts.value.toMutableMap()
                uMap[chatId] = (uMap[chatId] ?: 0) + 1
                _unreadCounts.value = uMap
            }
                        _conversations.value = map
            appCtxRef?.let { persistHistoryToDisk(it, map) }
        }
    }

    private fun persistHistoryToDisk(context: Context, map: Map<String, List<ChatMessage>>) {
        diskExecutor.execute {
            val sb = StringBuilder()
            map.forEach { (chatId, msgs) ->
                msgs.takeLast(150).forEach { m ->
                    val b64 = Base64.encodeToString(m.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    sb.append(chatId).append('\t')
                        .append(if (m.isMe) '1' else '0').append('\t')
                        .append(m.timeText).append('\t')
                        .append(m.statusBadge).append('\t')
                        .append(b64).append('\n')
                }
            }
            HardwareKeystore.saveEncryptedHistory(context, sb.toString())
        }
    }

    private fun loadHistoryFromDisk(context: Context) {
        cryptoExecutor.execute {
            val data = HardwareKeystore.loadEncryptedHistory(context) ?: return@execute
            val restored = mutableMapOf<String, MutableList<ChatMessage>>()
            data.lineSequence().forEach { line ->
                val p = line.split('\t')
                if (p.size == 5) {
                    val text = runCatching { String(Base64.decode(p[4], Base64.NO_WRAP), Charsets.UTF_8) }.getOrNull()
                        ?: return@forEach
                    restored.getOrPut(p[0]) { mutableListOf() }.add(ChatMessage(text, p[1] == "1", p[2], p[3]))
                }
            }
            if (restored.isNotEmpty()) {
                mainHandler.post { _conversations.value = restored }
            }
        }
    }

    private fun createMessageNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                MSG_CHANNEL_ID,
                "رسائل DesertComm الواردة",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                setShowBadge(true)
            }
            nm.createNotificationChannel(ch)
        }
    }

    @Suppress("DEPRECATION")
    private fun showIncomingNotification(context: Context, senderId: String, title: String, body: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra("open_chat_id", senderId)
        }
        val pi = PendingIntent.getActivity(
            context,
            senderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, MSG_CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
        val notif = builder
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setNumber((_unreadCounts.value.count { it.value > 0 }).coerceAtLeast(1))
            .setContentIntent(pi)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(senderId.hashCode(), notif)
    }
}
