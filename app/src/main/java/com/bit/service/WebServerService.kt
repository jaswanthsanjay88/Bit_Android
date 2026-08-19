package com.bit.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.bit.activity.MainActivity
import com.bit.network.server.WebAccessManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WebServerService : Service() {

    @Inject
    lateinit var webAccessManager: WebAccessManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var stateObserverJob: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        const val ACTION_START = "com.bit.action.WEB_SERVER_START"
        const val ACTION_STOP = "com.bit.action.WEB_SERVER_STOP"
        const val EXTRA_PORT = "port"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "bit_web_server_channel"
        private const val TAG = "WebServerService"

        fun start(context: Context, port: Int = 7070) {
            val intent = Intent(context, WebServerService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_PORT, port)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to startForegroundService", e)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, WebServerService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send stop intent", e)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 7070)
                startForegroundCompat(buildNotification("BIT Web Server Active on port $port"))
                acquireWifiLock()
                if (!webAccessManager.isRunning.value) {
                    webAccessManager.server.start(port)
                }
                startObservingState()
            }

            ACTION_STOP -> {
                webAccessManager.server.stop()
                releaseWifiLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            else -> {
                startForegroundCompat(buildNotification("BIT Web Server Active"))
                startObservingState()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stateObserverJob?.cancel()
        releaseWifiLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startObservingState() {
        if (stateObserverJob != null) return

        stateObserverJob = serviceScope.launch {
            webAccessManager.isRunning.collect { isRunning ->
                if (isRunning) {
                    val url = webAccessManager.serverUrl.value
                    updateNotification(buildNotification("Serving at $url"))
                } else {
                    releaseWifiLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BIT Web Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps BIT Web Server running in background"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, WebServerService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BIT Web Access")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Server", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wm?.createWifiLock(mode, "bit:web_server")
            wifiLock?.setReferenceCounted(false)
        }
        wifiLock?.acquire()
    }

    private fun releaseWifiLock() {
        try {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
            wifiLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wifi lock", e)
        }
    }
}
