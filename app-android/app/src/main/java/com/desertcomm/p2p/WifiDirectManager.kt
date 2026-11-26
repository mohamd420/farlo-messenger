package com.desertcomm.p2p

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
object WifiDirectManager {
    private const val TAG = "DesertWFD"
    private const val PORT = 8988

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private val ioExecutor = Executors.newCachedThreadPool()
    private val writeExecutor = Executors.newSingleThreadExecutor()

    private var serverSocket: ServerSocket? = null
    private var activeSocket: Socket? = null
    private var dataOut: DataOutputStream? = null

    private val _peers = MutableStateFlow<List<WifiP2pDevice>>(emptyList())
    val peers: StateFlow<List<WifiP2pDevice>> = _peers.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _status = MutableStateFlow("Wi-Fi Direct متوقف")
    val status: StateFlow<String> = _status.asStateFlow()

    var onConnectedReady: (() -> Unit)? = null
    var onFrameReceived: ((ByteArray) -> Unit)? = null

    fun start(context: Context) {
        if (manager != null) return
        val mgr = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager ?: return
        manager = mgr
        channel = mgr.initialize(context, Looper.getMainLooper(), null)

        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        mgr.requestPeers(channel) { list ->
                            _peers.value = list.deviceList.toList()
                        }
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        @Suppress("DEPRECATION")
                        val netInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                        } else {
                            intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                        }
                        if (netInfo?.isConnected == true) {
                            mgr.requestConnectionInfo(channel) { info -> handleConnectionInfo(info) }
                        } else {
                            _isConnected.value = false
                            closeSockets()
                        }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        discover()
    }

    fun discover() {
        val mgr = manager ?: return
        val ch = channel ?: return
        mgr.discoverPeers(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { _status.value = "Wi-Fi Direct: جاري البحث..." }
            override fun onFailure(reason: Int) { _status.value = "Wi-Fi Direct: فشل البحث ($reason)" }
        })
    }

    fun connect(device: WifiP2pDevice) {
        val mgr = manager ?: return
        val ch = channel ?: return
        val config = WifiP2pConfig().apply { deviceAddress = device.deviceAddress }
        _status.value = "Wi-Fi Direct: جاري الربط مع ${device.deviceName}..."
        mgr.connect(ch, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { _status.value = "Wi-Fi Direct: تم طلب الاتصال..." }
            override fun onFailure(reason: Int) { _status.value = "Wi-Fi Direct: فشل الربط ($reason)" }
        })
    }

    private fun handleConnectionInfo(info: WifiP2pInfo) {
        if (!info.groupFormed) return
        if (info.isGroupOwner) {
            _status.value = "Wi-Fi Direct: مضيف (Group Owner)"
            ioExecutor.execute {
                try {
                    serverSocket?.close()
                    val srv = ServerSocket(PORT)
                    serverSocket = srv
                    val sock = srv.accept()
                    attachSocket(sock)
                } catch (e: Exception) {
                    Log.e(TAG, "WFD server error: ${e.message}")
                }
            }
        } else {
            val host: InetAddress = info.groupOwnerAddress ?: return
            _status.value = "Wi-Fi Direct: عميل متصل بـ ${host.hostAddress}"
            ioExecutor.execute {
                try {
                    Thread.sleep(400)
                    val sock = Socket(host, PORT)
                    attachSocket(sock)
                } catch (e: Exception) {
                    Log.e(TAG, "WFD client error: ${e.message}")
                }
            }
        }
    }

    private fun attachSocket(sock: Socket) {
        activeSocket = sock
        dataOut = DataOutputStream(sock.getOutputStream())
        _isConnected.value = true
        _status.value = "Wi-Fi Direct: قناة سريعة جاهزة"
        onConnectedReady?.invoke()

        ioExecutor.execute {
            val input = DataInputStream(sock.getInputStream())
            try {
                while (sock.isConnected) {
                    val len = input.readInt()
                    if (len <= 0 || len > 4_194_304) break
                    val buf = ByteArray(len)
                    input.readFully(buf)
                    onFrameReceived?.invoke(buf)
                }
            } catch (_: Exception) {
            } finally {
                _isConnected.value = false
                closeSockets()
            }
        }
    }

    fun sendFrame(data: ByteArray) {
        writeExecutor.execute {
            try {
                val out = dataOut ?: return@execute
                out.writeInt(data.size)
                out.write(data)
                out.flush()
            } catch (e: Exception) {
                Log.e(TAG, "WFD send error: ${e.message}")
            }
        }
    }

    private fun closeSockets() {
        runCatching { dataOut?.close() }
        runCatching { activeSocket?.close() }
        runCatching { serverSocket?.close() }
        dataOut = null
        activeSocket = null
        serverSocket = null
    }

    fun stop(context: Context) {
        runCatching { receiver?.let { context.unregisterReceiver(it) } }
        receiver = null
        closeSockets()
    }
}