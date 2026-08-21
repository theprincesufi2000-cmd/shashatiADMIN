package com.sophy.admin

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast

class AdminService : Service() {
    companion object {
        const val ACTION_START = "com.sophy.admin.START"
        const val ACTION_STOP_BROADCAST = "com.sophy.admin.STOP_BROADCAST"
        const val ACTION_START_BROADCAST = "com.sophy.admin.START_BROADCAST"
        const val NOTIFICATION_ID = 5101
        const val CHANNEL_ID = "sophy_admin"
    }

    private lateinit var discovery: ReceiverDiscovery
    private lateinit var sender: AudioSender
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        AdminState.serviceRunning.set(true)
        createChannel()
        startAsForeground()
        acquireWakeLock()

        sender = AudioSender { updateNotification() }
        discovery = ReceiverDiscovery(
            context = this,
            onStateChanged = { updateNotification() },
            onReceiverFound = { host, port ->
                sender.setTarget(host, port)
                if (AutoBroadcastPrefs.enabled(this) && !AdminState.broadcasting.get()) {
                    sender.start()
                }
            }
        )
        discovery.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BROADCAST -> {
                AutoBroadcastPrefs.setEnabled(this, true)
                if (AdminState.receiverFound.get() && !AdminState.broadcasting.get()) sender.start()
                else AdminState.status = "بانتظار الشاشة…"
                updateNotification()
            }
            ACTION_STOP_BROADCAST -> {
                AutoBroadcastPrefs.setEnabled(this, false)
                sender.stop()
                updateNotification()
            }
        }
        return START_STICKY
    }

    fun startBroadcast() {
        if (AdminState.receiverFound.get() && !AdminState.broadcasting.get()) sender.start()
        else AdminState.status = "بانتظار الشاشة…"
        updateNotification()
    }

    fun stopBroadcast() {
        AutoBroadcastPrefs.setEnabled(this, false)
        sender.stop()
        updateNotification()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Sophy Admin audio streaming"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, AdminService::class.java).setAction(ACTION_STOP_BROADCAST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(notificationText())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .setShowWhen(false)
        if (AdminState.broadcasting.get()) {
            builder.addAction(Notification.Action.Builder(null, "إيقاف البث", stopIntent).build())
        }
        return builder.build()
    }

    private fun notificationText(): String {
        return when {
            AdminState.broadcasting.get() -> "● يبث الصوت إلى ${AdminState.receiverName}"
            AdminState.receiverFound.get() -> "متصل بـ ${AdminState.receiverName}"
            else -> "البحث عن شاشة Sophy…"
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "SophyAdmin:Audio"
        ).apply { acquire() }
    }

    override fun onDestroy() {
        try { discovery.stop() } catch (_: Throwable) {}
        try { sender.stop() } catch (_: Throwable) {}
        try { wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
        AdminState.serviceRunning.set(false)
        AdminState.broadcasting.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

object AutoBroadcastPrefs {
    private const val FILE = "sophy_admin"
    private const val KEY = "auto_broadcast"
    fun enabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(FILE, 0).getBoolean(KEY, false)
    fun setEnabled(context: android.content.Context, value: Boolean) =
        context.getSharedPreferences(FILE, 0).edit().putBoolean(KEY, value).apply()
}
