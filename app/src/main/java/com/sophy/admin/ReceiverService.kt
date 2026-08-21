package com.sophy.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder

class ReceiverService : Service() {
    companion object {
        const val ACTION_START = "com.sophy.receiver.START"
        const val NOTIFICATION_ID = 4101
        const val CHANNEL_ID = "sophy_receiver"
    }

    private lateinit var player: AudioPlayer
    private lateinit var network: NetworkReceiver
    private lateinit var advertiser: ServiceAdvertiser
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        ReceiverState.running.set(true)
        createChannel()
        startAsForeground()
        acquireLocks()

        player = AudioPlayer()
        advertiser = ServiceAdvertiser()
        network = NetworkReceiver { peer -> updateNotification(peer) }
        advertiser.start(this)
        network.start(player)
    }

    private fun startAsForeground() {
        val notification = buildNotification(ReceiverState.peer)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
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
                description = "خدمة استقبال الصوت عبر الشبكة المحلية"
                setShowBadge(false)
                setSound(null, null)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(peer: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(peer)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
            .setShowWhen(false)
            .build()
    }

    private fun updateNotification(peer: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(peer))
    }

    private fun acquireLocks() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        wakeLock = pm.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "SophyReceiver:Audio"
        ).apply { acquire() }

        val wm = getSystemService(WIFI_SERVICE) as WifiManager
        wifiLock = wm.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "SophyReceiver:WiFi"
        ).apply { setReferenceCounted(false); acquire() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        ReceiverState.running.set(false)
        ReceiverState.connected.set(false)
        try { network.stop() } catch (_: Throwable) {}
        try { advertiser.stop() } catch (_: Throwable) {}
        try { player.stop() } catch (_: Throwable) {}
        try { wifiLock?.release() } catch (_: Throwable) {}
        try { wakeLock?.release() } catch (_: Throwable) {}
        wifiLock = null
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
