package com.devaprakash.fastdrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Keeps the process, Wi-Fi radio and CPU awake at full speed while FastDrop is in use. */
class TransferService : Service() {
    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var idleSince = System.currentTimeMillis()
    private val engine get() = (application as App).engine

    private val idleCheck = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (engine.busy || engine.uiVisible) idleSince = now
            else if (now - idleSince > 30_000) { stopSelf(); return }
            handler.postDelayed(this, 5000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("fd", "FastDrop", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val quit = PendingIntent.getService(
            this, 1, Intent(this, TransferService::class.java).setAction("quit"), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "fd")
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("FastDrop")
            .setContentText("Ready to share files over Wi-Fi")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Quit", quit).build())
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        wake = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "fastdrop:xfer").apply { acquire(6 * 60 * 60 * 1000L) }
        wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "fastdrop").apply { acquire() }
        handler.postDelayed(idleCheck, 5000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "quit") {
            stopSelf()
            return START_NOT_STICKY
        }
        engine.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        engine.stop()
        try { wake?.release() } catch (_: Exception) {}
        try { wifi?.release() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
