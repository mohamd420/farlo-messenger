package com.desertcomm.p2p

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.torproject.jni.TorService

object EmbeddedTorManager {
    private val _status = MutableStateFlow("OFF")
    val status: StateFlow<String> = _status.asStateFlow()

    @Volatile var socksPort: Int = 9050
        private set

    private var bound = false
    private var torService: TorService? = null
    private var receiverRegistered = false
    private var onReadyCallback: ((Int) -> Unit)? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val st = intent?.getStringExtra(TorService.EXTRA_STATUS) ?: return
            _status.value = st
            if (st == TorService.STATUS_ON) {
                val p = runCatching { torService?.socksPort ?: 9050 }.getOrDefault(9050)
                if (p > 0) socksPort = p
                onReadyCallback?.invoke(socksPort)
            }
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val svc = (service as? TorService.LocalBinder)?.service
            torService = svc
            bound = true
            val p = runCatching { svc?.socksPort ?: 9050 }.getOrDefault(9050)
            if (p > 0) socksPort = p
            if (_status.value == TorService.STATUS_ON) {
                onReadyCallback?.invoke(socksPort)
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            torService = null
            _status.value = "OFF"
        }
    }

    fun start(context: Context, onReady: (Int) -> Unit) {
        val appCtx = context.applicationContext
        onReadyCallback = onReady
        _status.value = TorService.STATUS_STARTING
        if (!receiverRegistered) {
            val filter = IntentFilter(TorService.ACTION_STATUS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appCtx.registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                appCtx.registerReceiver(statusReceiver, filter)
            }
            receiverRegistered = true
        }
        if (!bound) {
            runCatching {
                appCtx.bindService(Intent(appCtx, TorService::class.java), conn, Context.BIND_AUTO_CREATE)
            }
        } else if (_status.value == TorService.STATUS_ON) {
            onReady(socksPort)
        }
    }

    fun stop(context: Context) {
        val appCtx = context.applicationContext
        onReadyCallback = null
        if (bound) {
            runCatching { appCtx.unbindService(conn) }
            bound = false
        }
        torService = null
        _status.value = "OFF"
    }
}