package com.henry.encodec.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager

class PlaybackService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(PlayerViewModel.MEDIA_NOTIFICATION_ID, preparingNotification())
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OndaBaja:Playback")
            .apply {
                setReferenceCounted(false)
            }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        setPlaybackActive(intent?.getBooleanExtra(EXTRA_PLAYBACK_ACTIVE, true) != false)
        PlayerViewModel.refreshMediaState()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        PlayerViewModel.dispatchMediaAction(MainActivity.ACTION_STOP)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun setPlaybackActive(active: Boolean) {
        val lock = wakeLock ?: return
        if (active && !lock.isHeld) {
            lock.acquire()
        } else if (!active && lock.isHeld) {
            lock.release()
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                PlayerViewModel.MEDIA_CHANNEL_ID,
                "OndaBaja",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Controles de radio en vivo" },
        )
    }

    private fun preparingNotification(): Notification =
        Notification.Builder(this, PlayerViewModel.MEDIA_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("OndaBaja")
            .setContentText("Preparando emisora…")
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()

    companion object {
        const val EXTRA_PLAYBACK_ACTIVE = "playback_active"
    }
}
