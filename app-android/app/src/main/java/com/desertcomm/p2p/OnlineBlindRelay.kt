package com.desertcomm.p2p

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object OnlineBlindRelay {
    private const val TAG = "SadaMultiRelay"
    private const val NOSTR_KIND = 1059

    private val defaultNostrRelays = mutableListOf(
        "wss://nos.lol",
        "wss://relay.primal.net",
        "wss://relay.damus.io",
        "wss://relay.nostr.bg",
        "wss://nostr.oxtr.dev"
    )

    private fun buildClient(useTor: Boolean, port: Int = 9050): OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        .pingInterval(55, TimeUnit.SECONDS)
        .connectTimeout(if (useTor) 20 else 10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .apply { if (useTor) proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))) }
        .build()

    @Volatile private var torEnabled: Boolean = false
    @Volatile private var client: OkHttpClient = buildClient(false)

    fun setTorEnabled(useTor: Boolean, port: Int = EmbeddedTorManager.socksPort) {
        if (torEnabled == useTor && !useTor) return
        torEnabled = useTor
        client = buildClient(useTor, port)
        if (enabled && subscribedTopics.isNotEmpty()) {
            connectAllNostrRelays()
            if (!baseUrl.contains("ntfy.sh")) connectNtfyWebSocket()
        }
    }

    private val chunkBuffer = mutableMapOf<String, MutableMap<Int, String>>()
    private const val MAX_CHUNK_CHARS = 2800
    private val reconnectExecutor = Executors.newSingleThreadScheduledExecutor()
    private val publishExecutor = Executors.newCachedThreadPool()

    private val nostrSockets = ConcurrentHashMap<String, WebSocket>()
    private val nostrConnected = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val seenEnvelopeIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private var ntfyWs: WebSocket? = null
    @Volatile private var ntfyConnected: Boolean = false
    @Volatile private var baseUrl: String = "https://ntfy.sh"
    @Volatile private var subscribedTopics: List<String> = emptyList()
    @Volatile private var enabled: Boolean = false
    @Volatile private var networkAvailable: Boolean = true

    private val _isOnlineConnected = MutableStateFlow(false)
    val isOnlineConnected: StateFlow<Boolean> = _isOnlineConnected.asStateFlow()

    private val _connectedRelayCount = MutableStateFlow(0)
    val connectedRelayCount: StateFlow<Int> = _connectedRelayCount.asStateFlow()

    private val _relayStatuses = MutableStateFlow(emptyMap<String, Boolean>())
    val relayStatuses = _relayStatuses.asStateFlow()

    fun addCustomRelay(url: String) {
        val clean = url.trim().trimEnd('/')
        if (clean.startsWith("ws://") || clean.startsWith("wss://")) {
            if (!defaultNostrRelays.contains(clean)) {
                defaultNostrRelays.add(clean)
                updateConnectionState()
                if (enabled) connectSingleNostrRelay(clean)
            }
        }
    }

    var onEnvelopeReceived: ((String) -> Unit)? = null

    fun topicForTag(destTag: String): String = "sada_v4_${destTag.uppercase()}"

    private fun updateConnectionState() {
        val total = nostrConnected.size + (if (ntfyConnected) 1 else 0)
        val statusMap = linkedMapOf<String, Boolean>()
        defaultNostrRelays.forEach { r -> statusMap[r] = nostrConnected.contains(r) }
        statusMap[baseUrl] = ntfyConnected
        _relayStatuses.value = statusMap
        _connectedRelayCount.value = total
        _isOnlineConnected.value = total > 0
    }

    fun configureAndStart(serverUrl: String, destTags: List<String>, isEnabled: Boolean) {
        baseUrl = serverUrl.trim().trimEnd('/').ifEmpty { "https://ntfy.sh" }
        subscribedTopics = destTags.distinct().map { topicForTag(it) }
        enabled = isEnabled
        networkAvailable = true
        if (!enabled || subscribedTopics.isEmpty()) {
            stop()
            return
        }
        connectAllNostrRelays()
        if (!baseUrl.contains("ntfy.sh")) connectNtfyWebSocket()
    }

    fun onNetworkAvailable() {
        networkAvailable = true
        ensureConnected()
    }

    fun onNetworkLost() {
        networkAvailable = false
        nostrSockets.values.forEach { runCatching { it.cancel() } }
        nostrSockets.clear()
        nostrConnected.clear()
        runCatching { ntfyWs?.cancel() }
        ntfyWs = null
        ntfyConnected = false
        updateConnectionState()
    }

    fun ensureConnected() {
        if (!enabled || !networkAvailable || subscribedTopics.isEmpty()) return
        for (relayUrl in defaultNostrRelays) {
            if (!nostrConnected.contains(relayUrl) && !nostrSockets.containsKey(relayUrl)) {
                connectSingleNostrRelay(relayUrl)
            }
        }
        if (!ntfyConnected && ntfyWs == null) {
            if (!baseUrl.contains("ntfy.sh")) connectNtfyWebSocket()
        }
    }

    fun stop() {
        enabled = false
        nostrSockets.values.forEach { runCatching { it.close(1000, "Air-Gap") } }
        nostrSockets.clear()
        nostrConnected.clear()
        runCatching { ntfyWs?.close(1000, "Air-Gap") }
        ntfyWs = null
        ntfyConnected = false
        updateConnectionState()
    }

    private fun connectAllNostrRelays() {
        nostrSockets.values.forEach { runCatching { it.cancel() } }
        nostrSockets.clear()
        nostrConnected.clear()
        updateConnectionState()
        for (relayUrl in defaultNostrRelays) {
            connectSingleNostrRelay(relayUrl)
        }
    }

    private fun connectSingleNostrRelay(relayUrl: String) {
        if (!enabled || !networkAvailable || subscribedTopics.isEmpty()) return
        val req = Request.Builder().url(relayUrl).build()
        val ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                nostrConnected.add(relayUrl)
                updateConnectionState()
                val since = (System.currentTimeMillis() / 1000L) - 43200L
                val tagsJson = JSONArray(subscribedTopics.map { NostrCrypto.sha256Hex(it) })
                val filter = JSONObject().apply {
                    put("kinds", JSONArray(listOf(NOSTR_KIND)))
                    put("#p", tagsJson)
                    put("since", since)
                }
                val reqMsg = JSONArray().apply {
                    put("REQ")
                    put("sada_sub")
                    put(filter)
                }.toString()
                webSocket.send(reqMsg)
                Log.d(TAG, "Nostr relay connected: $relayUrl")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val arr = JSONArray(text)
                    if (arr.length() >= 3 && arr.optString(0) == "EVENT") {
                        val ev = arr.optJSONObject(2) ?: return
                        val content = ev.optString("content", "")
                        deliverPayload(content)
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                nostrConnected.remove(relayUrl)
                nostrSockets.remove(relayUrl)
                updateConnectionState()
                if (enabled) {
                    reconnectExecutor.schedule({
                        if (enabled && !nostrConnected.contains(relayUrl)) connectSingleNostrRelay(relayUrl)
                    }, 2L, TimeUnit.SECONDS)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                nostrConnected.remove(relayUrl)
                nostrSockets.remove(relayUrl)
                updateConnectionState()
                if (enabled) {
                    reconnectExecutor.schedule({
                        if (enabled && !nostrConnected.contains(relayUrl)) connectSingleNostrRelay(relayUrl)
                    }, 2L, TimeUnit.SECONDS)
                }
            }
        })
        nostrSockets[relayUrl] = ws
    }

    private fun connectNtfyWebSocket() {
        if (!enabled || !networkAvailable || subscribedTopics.isEmpty()) return
        runCatching { ntfyWs?.cancel() }
        val wsBase = baseUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val topicsPath = subscribedTopics.joinToString(",")
        val url = "$wsBase/$topicsPath/ws?since=12h"
        val req = Request.Builder().url(url).build()
        ntfyWs = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ntfyConnected = true
                updateConnectionState()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val obj = JSONObject(text)
                    if (obj.optString("event") == "message") {
                        deliverPayload(obj.optString("message", ""))
                    }
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                ntfyConnected = false
                ntfyWs = null
                updateConnectionState()
                if (enabled) {
                    reconnectExecutor.schedule({
                        if (enabled && !ntfyConnected) connectNtfyWebSocket()
                    }, 15L, TimeUnit.SECONDS)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                ntfyConnected = false
                ntfyWs = null
                updateConnectionState()
            }
        })
    }

    private fun deliverPayload(msg: String) {
        when {
            msg.startsWith("DTN|") -> {
                val msgUid = msg.split("|", limit = 4).getOrNull(1) ?: msg.hashCode().toString()
                if (seenEnvelopeIds.add(msgUid)) {
                    if (seenEnvelopeIds.size > 2000) seenEnvelopeIds.clear()
                    onEnvelopeReceived?.invoke(msg)
                }
            }
            msg.startsWith("CHK|") -> handleIncomingChunk(msg)
        }
    }

    private fun handleIncomingChunk(raw: String) {
        val p = raw.split("|", limit = 5)
        if (p.size != 5) return
        val cid = p[1]
        val idx = p[2].toIntOrNull() ?: return
        val total = p[3].toIntOrNull() ?: return
        val part = p[4]
        val assembled = synchronized(chunkBuffer) {
            val map = chunkBuffer.getOrPut(cid) { mutableMapOf() }
            map[idx] = part
            if (map.size == total) {
                val full = (0 until total).joinToString("") { map[it] ?: "" }
                chunkBuffer.remove(cid)
                full
            } else null
        }
        if (assembled != null && assembled.startsWith("DTN|")) {
            deliverPayload(assembled)
        }
    }

    fun publishEnvelope(destTag: String, dtnEnvelope: String, onResult: ((Boolean) -> Unit)? = null) {
        if (!enabled) {
            onResult?.invoke(false)
            return
        }
        val topic = topicForTag(destTag)
        var sentViaNostr = false

        // 1) البث اللامركزي الفوري عبر جميع مرحّلات Nostr المتصلة (مجاني تماماً وبدون 429)
        if (nostrSockets.isNotEmpty()) { publishExecutor.execute {
            runCatching {
                val eventJson = NostrCrypto.buildSignedEventJson(NostrCrypto.sha256Hex(topic), dtnEnvelope, NOSTR_KIND)
                var sentCount = 0
                for ((url, ws) in nostrSockets) {
                    if (nostrConnected.contains(url) && ws.send(eventJson)) {
                        sentCount++
                    }
                }
                if (sentCount > 0) onResult?.invoke(true) else { ensureConnected(); onResult?.invoke(false) }
            }
        }; return }

        if (sentViaNostr) {
            onResult?.invoke(true)
            // بث إضافي صامت عبر ntfy فقط إذا كان المستخدم يستخدم رابطاً خاصاً غير الافتراضي
            if (!baseUrl.contains("ntfy.sh")) {
                publishViaNtfy(topic, dtnEnvelope, null)
            }
            return
        }

        // 2) الاحتياط عبر ntfy إذا لم يتصل أي مرحّل Nostr بعد
        publishViaNtfy(topic, dtnEnvelope, onResult)
    }

    private fun publishViaNtfy(topic: String, dtnEnvelope: String, onResult: ((Boolean) -> Unit)?) {
        val url = "$baseUrl/$topic"
        if (dtnEnvelope.length <= 6000) {
            postRaw(url, dtnEnvelope, true, onResult)
            return
        }
        val cid = java.util.UUID.randomUUID().toString().take(8)
        val parts = dtnEnvelope.chunked(MAX_CHUNK_CHARS)
        val total = parts.size
        val okCount = AtomicInteger(0)
        val doneCount = AtomicInteger(0)
        parts.forEachIndexed { idx, part ->
            val chunkMsg = "CHK|$cid|$idx|$total|$part"
            postRaw(url, chunkMsg, true) { ok ->
                if (ok) okCount.incrementAndGet()
                if (doneCount.incrementAndGet() == total) {
                    onResult?.invoke(okCount.get() == total)
                }
            }
        }
    }

    private fun postRaw(url: String, text: String, retryOn429: Boolean = true, onResult: ((Boolean) -> Unit)? = null) {
        val body = text.toByteArray(Charsets.UTF_8).toRequestBody("text/plain; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onResult?.invoke(false) }
            override fun onResponse(call: Call, response: Response) {
                val code = response.code
                val ok = response.isSuccessful
                response.close()
                if (code == 429 && retryOn429) {
                    reconnectExecutor.schedule({ postRaw(url, text, retryOn429 = false, onResult = onResult) }, 4L, TimeUnit.SECONDS)
                    return
                }
                onResult?.invoke(ok)
            }
        })
    }
}
