package com.sophy.admin

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
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
        const val ACTION_START_VIDEO = "com.sophy.admin.START_VIDEO"
        const val ACTION_STOP_VIDEO = "com.sophy.admin.STOP_VIDEO"
        const val NOTIFICATION_ID = 5101
        const val CHANNEL_ID = "sophy_admin"
    }

    private lateinit var discovery: ReceiverDiscovery
    private lateinit var sender: AudioSender
    private lateinit var cameraSender: CameraSender
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        AdminState.serviceRunning.set(true)
        createChannel()
        startAsForeground()
        acquireWakeLock()

        sender = AudioSender { updateNotification() }
        cameraSender = CameraSender(this) { updateNotification() }
        discovery = ReceiverDiscovery(
            context = this,
            onStateChanged = { updateNotification() },
            onReceiverFound = { host, port ->
                sender.setTarget(host, port)
                cameraSender.setTarget(host, port)
                if (AutoBroadcastPrefs.enabled(this) && !AdminState.broadcasting.get()) {
                    sender.start()
                }
                if (AutoVideoPrefs.enabled(this) && !cameraSender.isRunning() && hasCameraPermission()) {
                    cameraSender.start()
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
            ACTION_START_VIDEO -> {
                AutoVideoPrefs.setEnabled(this, true)
                when {
                    !hasCameraPermission() -> AdminState.videoStatus = "صلاحية الكاميرا مطلوبة"
                    AdminState.receiverFound.get() && !cameraSender.isRunning() -> {
                        startAsForeground()
                        cameraSender.start()
                    }
                    else -> AdminState.videoStatus = "بانتظار الشاشة…"
                }
                updateNotification()
            }
            ACTION_STOP_VIDEO -> {
                AutoVideoPrefs.setEnabled(this, false)
                cameraSender.stop()
                startAsForeground()
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

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (hasCameraPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Sophy Admin",
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
            this, 0, packageManager.getLaunchIntentForPackage(packageName) ?: Intent(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, AdminService::class.java).setAction(ACTION_STOP_BROADCAST),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopVideoIntent = PendingIntent.getService(
            this, 2, Intent(this, AdminService::class.java).setAction(ACTION_STOP_VIDEO),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Sophy Admin")
            .setContentText(notificationText())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .setShowWhen(false)
        if (AdminState.broadcasting.get()) {
            builder.addAction(Notification.Action.Builder(null, "إيقاف الصوت", stopIntent).build())
        }
        if (AdminState.videoBroadcasting.get()) {
            builder.addAction(Notification.Action.Builder(null, "إيقاف الكاميرا", stopVideoIntent).build())
        }
        return builder.build()
    }

    private fun notificationText(): String {
        val parts = mutableListOf<String>()
        parts += when {
            AdminState.broadcasting.get() -> "● يبث الصوت"
            AdminState.receiverFound.get() -> "متصل بـ ${AdminState.receiverName}"
            else -> "البحث عن شاشة Sophy…"
        }
        if (AdminState.videoBroadcasting.get()) parts += "● يبث الكاميرا"
        return parts.joinToString("  ")
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
        try { cameraSender.stop() } catch (_: Throwable) {}
        try { wakeLock?.release() } catch (_: Throwable) {}
        wakeLock = null
        AdminState.serviceRunning.set(false)
        AdminState.broadcasting.set(false)
        AdminState.videoBroadcasting.set(false)
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

object AutoVideoPrefs {
    private const val FILE = "sophy_admin"
    private const val KEY = "auto_video"
    fun enabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(FILE, 0).getBoolean(KEY, false)
    fun setEnabled(context: android.content.Context, value: Boolean) =
        context.getSharedPreferences(FILE, 0).edit().putBoolean(KEY, value).apply()
}
