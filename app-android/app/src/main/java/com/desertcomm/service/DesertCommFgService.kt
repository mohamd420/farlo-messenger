package com.desertcomm.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.desertcomm.MainActivity
import com.desertcomm.core.ChatRepository
import com.desertcomm.core.NativeBridgeV3
import com.desertcomm.p2p.OnlineBlindRelay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DesertCommFgService : Service() {
    companion object {
        const val EXTRA_TACTICAL_MODE = "tactical_mode"
        private const val CHANNEL_ID = "desertcomm_bg_silent_v2"
        private const val NOTIF_ID = 3101

        private val _stratum = MutableStateFlow(4)
        val stratum: StateFlow<Int> = _stratum.asStateFlow()

        private val _tacticalMode = MutableStateFlow(false)
        val tacticalMode: StateFlow<Boolean> = _tacticalMode.asStateFlow()
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastDriftGapMs: Long? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            val uptime = SystemClock.uptimeMillis()
            val boottime = SystemClock.elapsedRealtime()
            val gap = boottime - uptime
            val sleepDetected = lastDriftGapMs?.let { kotlin.math.abs(gap - it) > 2_000L } ?: false
            lastDriftGapMs = gap

            NativeBridgeV3.safeTickClock(uptime, sleepDetected).onSuccess { s ->
                _stratum.value = s
            }
            if (ChatRepository.onlineEnabled.value) {
                OnlineBlindRelay.ensureConnected()
            }
            handler.postDelayed(this, 180_000L)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isTactical = intent?.getBooleanExtra(EXTRA_TACTICAL_MODE, false) ?: _tacticalMode.value
        _tacticalMode.value = isTactical
        ChatRepository.startEngine(applicationContext)

        createNotificationChannel()
        val notif = buildNotification(isTactical)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            runCatching { startForeground(NOTIF_ID, notif, fgType) }
                .onFailure { startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) }
        } else {
            startForeground(NOTIF_ID, notif)
        }

        if (isTactical) {
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }

        registerNetworkWatcher()
        OnlineBlindRelay.ensureConnected()

        handler.removeCallbacks(tickRunnable)
        handler.post(tickRunnable)
        return START_STICKY
    }

    private fun registerNetworkWatcher() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                val stillHasNet = cm.activeNetwork != null
                if (!stillHasNet) OnlineBlindRelay.onNetworkLost()
            }
            override fun onAvailable(network: Network) {
                handler.postDelayed({ OnlineBlindRelay.onNetworkAvailable() }, 1_000L)
            }
        }
        runCatching {
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // إعادة تشغيل الخدمة تلقائياً إذا تم مسح التطبيق من القائمة الأخيرة
        val restartIntent = Intent(applicationContext, DesertCommFgService::class.java).apply {
            putExtra(EXTRA_TACTICAL_MODE, _tacticalMode.value)
        }
        val pi = PendingIntent.getService(
            applicationContext, 3109, restartIntent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        am?.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 2_000L, pi)
        super.onTaskRemoved(rootIntent)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DesertComm::BackgroundRelayLock").apply {
            acquire(24 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "Farlo Radio Core", NotificationManager.IMPORTANCE_MIN)
            nm.createNotificationChannel(ch)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(isTactical: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(this, 3102, openIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val modeLabel = if (isTactical) "الطور التكتيكي (WakeLock + CRV)" else "متصل بالخلفية لاستقبال الرسائل الفورية"
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Farlo")
            .setContentText(modeLabel)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(openPi)
            .setOngoing(false).setPriority(Notification.PRIORITY_MIN).setShowWhen(false)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tickRunnable)
        networkCallback?.let { cb ->
            runCatching {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}