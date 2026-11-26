package com.desertcomm.p2p

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.Executors

object WispLanMesh {
    private const val TAG = "DesertWISP"
    private const val PORT = 49155
    private const val MULTICAST_GROUP = "239.255.77.77"

    private val ioExecutor = Executors.newCachedThreadPool()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var rxSocket: MulticastSocket? = null
    @Volatile private var running = false

    var onPacketReceived: ((String) -> Unit)? = null

    fun start(context: Context) {
        if (running) return
        running = true

        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wm?.createMulticastLock("DesertComm::WispMeshLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        ioExecutor.execute {
            try {
                val sock = MulticastSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(PORT))
                }
                rxSocket = sock
                runCatching {
                    val group = InetAddress.getByName(MULTICAST_GROUP)
                    sock.joinGroup(group)
                }

                val buf = ByteArray(2048)
                while (running) {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    val text = String(pkt.data, pkt.offset, pkt.length, Charsets.UTF_8)
                    if (text.startsWith("DTN|")) {
                        onPacketReceived?.invoke(text)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "WISP mesh listener stopped: ${e.message}")
            }
        }
    }

    fun setForegroundPower(active: Boolean) {
        runCatching {
            val lock = multicastLock ?: return
            if (active && !lock.isHeld) lock.acquire()
            else if (!active && lock.isHeld) lock.release()
        }
    }

    fun broadcastDtnEnvelope(dtnEnvelope: String) {
        ioExecutor.execute {
            val bytes = dtnEnvelope.toByteArray(Charsets.UTF_8)
            if (bytes.size > 1400) return@execute
            try {
                DatagramSocket().use { sock ->
                    sock.broadcast = true
                    // 1) بث عام و Multicast
                    val targets = mutableSetOf<InetAddress>(
                        InetAddress.getByName("255.255.255.255"),
                        InetAddress.getByName(MULTICAST_GROUP)
                    )
                    // 2) عناوين البث الفرعية لكل واجهة شبكة نشطة (مثل 192.168.x.255 أو 10.x.x.255 في أبراج WISP)
                    val interfaces = NetworkInterface.getNetworkInterfaces()
                    while (interfaces != null && interfaces.hasMoreElements()) {
                        val nif = interfaces.nextElement()
                        if (!nif.isUp || nif.isLoopback) continue
                        for (ia in nif.interfaceAddresses) {
                            ia.broadcast?.let { targets.add(it) }
                        }
                    }
                    for (addr in targets) {
                        runCatching {
                            sock.send(DatagramPacket(bytes, bytes.size, addr, PORT))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "WISP broadcast error: ${e.message}")
            }
        }
    }
}