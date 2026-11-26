package com.desertcomm

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class PeerDevice(
    val device: BluetoothDevice,
    val id: String,
    val psm: Int,
    val lastSeenMs: Long = System.currentTimeMillis()
)

enum class BleState {
    IDLE,
    ADVERTISING_AND_SCANNING,
    CONNECTING,
    DISCOVERING_SERVICES,
    ENABLING_NOTIFICATIONS,
    READY_FOR_HANDSHAKE,
    CONNECTED_SECURE,
    ERROR
}

@SuppressLint("MissingPermission")
object BleManager {
    private const val TAG = "DesertBLE"

    val SERVICE_UUID: UUID = UUID.fromString("0000ffff-0000-1000-8000-00805f9b34fb")

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newCachedThreadPool()
    private val writeExecutor = Executors.newSingleThreadExecutor()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bleAdvertiser: BluetoothLeAdvertiser? = null
    private var bleScanner: BluetoothLeScanner? = null

    private var serverSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var dataOut: DataOutputStream? = null

    private var myLocalId: String = ""
    private var localPsm: Int = -1
    private var isAdvertising = false
    private var isScanning = false
    @Volatile private var highPowerMode = true
    private val isConnecting = AtomicBoolean(false)
    @Volatile var isServer = false
        private set
    @Volatile var isHandshakeCompleted = false
    @Volatile var connectedPeerId: String? = null

    private val _bleState = MutableStateFlow(BleState.IDLE)
    val bleState: StateFlow<BleState> = _bleState.asStateFlow()

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val _statusText = MutableStateFlow("تهيئة البلوتوث...")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    private val _packetProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val packetProgress: StateFlow<Pair<Int, Int>?> = _packetProgress.asStateFlow()

    var onReadyForHandshake: ((Boolean) -> Unit)? = null
    var onHandshakeReceived: ((ByteArray) -> Unit)? = null
    var onDataReceived: ((ByteArray) -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null

    fun init(context: Context) {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = manager.adapter
        bleAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser
        bleScanner = bluetoothAdapter?.bluetoothLeScanner
    }

    fun startDiscoveryAndServer(context: Context, myId: String) {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            updateStatus(BleState.ERROR, "البلوتوث غير مفعّل على الجهاز")
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            updateStatus(BleState.ERROR, "يتطلب أندرويد 10 أو أحدث لقناة BLE L2CAP")
            return
        }

        myLocalId = myId
        _peers.value = emptyList()

        if (serverSocket == null) {
            try {
                val srv = adapter.listenUsingInsecureL2capChannel()
                serverSocket = srv
                localPsm = srv.psm
                Log.d(TAG, "BLE L2CAP server listening on PSM=$localPsm")

                ioExecutor.execute {
                    try {
                        while (true) {
                            val clientSock = srv.accept() ?: break
                            isConnecting.set(false)
                            isServer = true
                            isHandshakeCompleted = false
                            stopScanning()
                            attachSocket(clientSock, serverMode = true)
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "L2CAP server stopped: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                updateStatus(BleState.ERROR, "فشل فتح قناة L2CAP: ${e.message}")
                return
            }
        }

        if (!isAdvertising && localPsm > 0) {
            val payload = "$myId|$localPsm".toByteArray(Charsets.UTF_8)
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(if (highPowerMode) AdvertiseSettings.ADVERTISE_MODE_BALANCED else AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .addServiceData(ParcelUuid(SERVICE_UUID), payload)
                .build()
            bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
            isAdvertising = true
        }

        if (!isScanning) {
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(SERVICE_UUID))
                .build()
            val scanSettings = ScanSettings.Builder()
                .setScanMode(if (highPowerMode) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER)
                .build()
            bleScanner?.startScan(listOf(filter), scanSettings, scanCallback)
            isScanning = true; if (activeSocket == null) _bleState.value = BleState.ADVERTISING_AND_SCANNING
        }

        updateStatus(BleState.ADVERTISING_AND_SCANNING, "بحث (طاقة قصوى | PSM $localPsm)...")
    }

        private fun resumeScanning(delayMs: Long = 500L) {
        mainHandler.postDelayed({
            if (!isScanning && bluetoothAdapter?.isEnabled == true && activeSocket == null) {
                val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
                val scanSettings = ScanSettings.Builder().setScanMode(if (highPowerMode) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER).build()
                runCatching {
                    bleScanner?.startScan(listOf(filter), scanSettings, scanCallback)
                    isScanning = true; if (activeSocket == null) _bleState.value = BleState.ADVERTISING_AND_SCANNING
                }
            }
        }, delayMs)
    }

    fun connectToDevice(context: Context, device: BluetoothDevice) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (activeSocket?.isConnected == true) return
        if (!isConnecting.compareAndSet(false, true)) return

        val peer = _peers.value.firstOrNull { it.device.address == device.address }
        if (peer == null || peer.psm <= 0) {
            isConnecting.set(false)
            updateStatus(BleState.ADVERTISING_AND_SCANNING, "جاري تحديث عنوان الجهاز...")
            resumeScanning(300L)
            return
        }

        stopScanning()
        isServer = false
        isHandshakeCompleted = false
        connectedPeerId = peer.id
        updateStatus(BleState.CONNECTING, "جاري الاتصال بـ ${peer.id}...")

        ioExecutor.execute {
            try {
                closeActiveSocket()
                val sock = device.createInsecureL2capChannel(peer.psm)
                sock.connect()
                isConnecting.set(false)
                attachSocket(sock, serverMode = false)
            } catch (e: Exception) {
                isConnecting.set(false)
                Log.e(TAG, "L2CAP connect failed: ${e.message}", e)
                // حذف العنوان القديم ليجلب المسح العنوان والمنفذ المحدثين
                _peers.value = _peers.value.filterNot { it.device.address == device.address }
                updateStatus(BleState.ADVERTISING_AND_SCANNING, "إعادة مزامنة القناة مع ${peer.id}...")
                resumeScanning(600L)
            }
        }
    }

    private fun attachSocket(socket: BluetoothSocket, serverMode: Boolean) {
        activeSocket = socket
        dataOut = DataOutputStream(socket.outputStream)
        val label = if (serverMode) "متصل (خادم L2CAP) - بانتظار المصافحة..." else "بدء المصافحة المشفرة..."
        updateStatus(BleState.READY_FOR_HANDSHAKE, label)
        mainHandler.post { onReadyForHandshake?.invoke(serverMode) }

        ioExecutor.execute {
            val input = DataInputStream(socket.inputStream)
            try {
                while (socket.isConnected) {
                    val len = input.readInt()
                    if (len <= 0 || len > 1_048_576) {
                        throw IllegalStateException("Invalid frame length: $len")
                    }
                    val payload = ByteArray(len)
                    input.readFully(payload)
                    if (!isHandshakeCompleted && payload.size == 32) {
                        onHandshakeReceived?.invoke(payload)
                    } else {
                        onDataReceived?.invoke(payload)
                    }
                }
            } catch (_: EOFException) {
                updateStatus(BleState.ADVERTISING_AND_SCANNING, "جاهز للاتصال...")
            } catch (e: Exception) {
                Log.e(TAG, "Read loop ended: ${e.message}")
                updateStatus(BleState.ADVERTISING_AND_SCANNING, "جاهز للاتصال...")
            } finally {
                isHandshakeCompleted = false
                connectedPeerId = null
                _packetProgress.value = null
                onDisconnected?.invoke()
                closeActiveSocket()
                mainHandler.postDelayed({
                    if (!isScanning && bluetoothAdapter?.isEnabled == true) {
                        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
                        val scanSettings = ScanSettings.Builder().setScanMode(if (highPowerMode) ScanSettings.SCAN_MODE_BALANCED else ScanSettings.SCAN_MODE_LOW_POWER).build()
                        try {
                            bleScanner?.startScan(listOf(filter), scanSettings, scanCallback)
                            isScanning = true; if (activeSocket == null) _bleState.value = BleState.ADVERTISING_AND_SCANNING
                        } catch (_: Exception) {}
                    }
                }, 800L)
            }
        }
    }

    fun sendHandshake(publicKey: ByteArray) {
        writeFrame(publicKey)
    }

    fun sendPackets(packets: List<ByteArray>) {
        if (packets.isEmpty()) return
        val total = packets.size
        writeExecutor.execute {
            packets.forEachIndexed { idx, pkt ->
                if (writeFrameBlocking(pkt)) {
                    _packetProgress.value = (idx + 1) to total
                }
            }
            _packetProgress.value = null
        }
    }

    private fun writeFrame(data: ByteArray) {
        writeExecutor.execute {
            writeFrameBlocking(data)
        }
    }

    private fun writeFrameBlocking(data: ByteArray): Boolean {
        return try {
            val out = dataOut ?: run {
                updateStatus(BleState.ERROR, "قناة الإرسال غير مفتوحة")
                return false
            }
            out.writeInt(data.size)
            out.write(data)
            out.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Write frame failed: ${e.message}")
            updateStatus(BleState.ERROR, "فشل الإرسال: ${e.message}")
            false
        }
    }

    private var burstSetCallback: AdvertisingSetCallback? = null
    var onAirBurstReceived: ((String) -> Unit)? = null

    fun broadcastAirBurst(dtnEnvelope: String) { /* معطل لمنع تعارض معلن BLE الأساسي */ }

    fun setStatus(message: String) {
        _statusText.value = message
    }

    fun markSecureConnected() {
        isHandshakeCompleted = true
        _packetProgress.value = null
        updateStatus(BleState.CONNECTED_SECURE, "آمن - مفاتيح الجلسة جاهزة")
    }

    fun setPowerMode(foregroundActive: Boolean) {
        if (highPowerMode == foregroundActive) return
        highPowerMode = foregroundActive
        if (activeSocket != null || bluetoothAdapter?.isEnabled != true) return
        // إعادة ضبط المسح والبث بوضع الطاقة المناسب (BALANCED أو LOW_POWER)
        if (isScanning) {
            stopScanning()
            resumeScanning(300L)
        }
        if (isAdvertising && localPsm > 0 && myLocalId.isNotEmpty()) {
            stopAdvertising()
            val payload = "$myLocalId|$localPsm".toByteArray(Charsets.UTF_8)
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(if (highPowerMode) AdvertiseSettings.ADVERTISE_MODE_BALANCED else AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setTxPowerLevel(if (highPowerMode) AdvertiseSettings.ADVERTISE_TX_POWER_HIGH else AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build()
            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .addServiceUuid(ParcelUuid(SERVICE_UUID))
                .addServiceData(ParcelUuid(SERVICE_UUID), payload)
                .build()
            runCatching {
                bleAdvertiser?.startAdvertising(settings, data, advertiseCallback)
                isAdvertising = true
            }
        }
    }

    fun disconnectSilentForNextHop() {
        isHandshakeCompleted = false
        connectedPeerId = null
        closeActiveSocket()
        _bleState.value = BleState.ADVERTISING_AND_SCANNING
        resumeScanning(600L)
    }

    fun disconnect() {
        stopScanning()
        stopAdvertising()
        isHandshakeCompleted = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        localPsm = -1
        closeActiveSocket()
        updateStatus(BleState.IDLE, "تم قطع الاتصال")
    }

    private fun closeActiveSocket() {
        try { dataOut?.close() } catch (_: Exception) {}
        try { activeSocket?.close() } catch (_: Exception) {}
        dataOut = null
        activeSocket = null
    }

    private fun stopScanning() {
        if (isScanning) {
            try { bleScanner?.stopScan(scanCallback) } catch (_: Exception) {}
            isScanning = false
        }
    }

    private fun stopAdvertising() {
        if (isAdvertising) {
            try { bleAdvertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
            isAdvertising = false
        }
    }

    private fun updateStatus(state: BleState, message: String) {
        _bleState.value = state
        _statusText.value = message
    }

        private val advertisingSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            Log.d(TAG, "Coded PHY Advertising started: status=$status txPower=$txPower")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising started (PSM=$localPsm)")
        }

        override fun onStartFailure(errorCode: Int) {
            updateStatus(BleState.ERROR, "فشل البث (كود $errorCode)")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val serviceData = result.scanRecord?.getServiceData(ParcelUuid(SERVICE_UUID)) ?: return
            val raw = String(serviceData, Charsets.UTF_8)
            if (raw.startsWith("DTN|")) { onAirBurstReceived?.invoke(raw); return }
            val parts = raw.split("|")
            val peerId = parts.getOrNull(0) ?: return
            val psm = parts.getOrNull(1)?.toIntOrNull() ?: return
            if (!peerId.startsWith("DC-") || peerId == myLocalId) return

            val now = System.currentTimeMillis()
            val existing = _peers.value.firstOrNull { it.id == peerId }
            if (existing != null && existing.psm == psm && existing.device.address == result.device.address && (now - existing.lastSeenMs) < 8_000L) {
                return
            }
            val updated = _peers.value
                .filter { now - it.lastSeenMs < 20_000L && it.id != peerId && it.device.address != result.device.address } +
                PeerDevice(result.device, peerId, psm, now)
            _peers.value = updated
        }
    }
}