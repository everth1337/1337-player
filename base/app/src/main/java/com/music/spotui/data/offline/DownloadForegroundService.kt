package com.music.spotui.data.offline

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Binder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.myspoti.engine.R

/** Keeps a user-started music download queue visible and alive independently of playback. */
class DownloadForegroundService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var promoted = false
    private val update = object : Runnable {
        override fun run() {
            val left = OfflineDownloader.pendingCount()
            if (left == 0 || OfflineDownloader.backgroundPaused) {
                stopSelf()
                return
            }
            getSystemService(NotificationManager::class.java).notify(ID, notification(left))
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.player_download_channel), NotificationManager.IMPORTANCE_LOW))
        try {
            startForeground(ID, notification(OfflineDownloader.pendingCount()), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            promoted = true
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:downloads").apply {
                    setReferenceCounted(false)
                    // Android 15+ also enforces the dataSync limit through onTimeout.
                    acquire(6L * 60 * 60 * 1000)
                }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Foreground download start refused: ${e.javaClass.simpleName}")
            OfflineDownloader.pauseForSystem()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) OfflineDownloader.stopAll()
        handler.removeCallbacks(update)
        if (promoted) handler.post(update) else stopSelf()
        // A killed process resumes its durable queue when the user opens the player.
        return START_NOT_STICKY
    }

    internal class LocalBinder(val service: DownloadForegroundService) : Binder()
    override fun onBind(intent: Intent?): IBinder = LocalBinder(this)

    override fun onTimeout(startId: Int, fgsType: Int) {
        OfflineDownloader.pauseForSystem()
        handler.removeCallbacks(update)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        val notice = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.player_download_paused))
            .setContentText(getString(R.string.player_download_resume))
            .setContentIntent(openPlayer()).setAutoCancel(true).build()
        getSystemService(NotificationManager::class.java).notify(PAUSED_ID, notice)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun openPlayer() = PendingIntent.getActivity(this, ID,
        Intent(this, app.myspoti.MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun notification(left: Int): Notification {
        val stop = PendingIntent.getService(this, ID,
            Intent(this, DownloadForegroundService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.player_download_channel))
            .setContentText(getString(R.string.player_download_remaining, left))
            .setContentIntent(openPlayer()).setOnlyAlertOnce(true).setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.downloads_stop_all_title), stop)
            .build()
    }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL = "player_downloads"
        private const val ID = 1337001
        private const val PAUSED_ID = 1337002
        private const val STOP = "app.nexi.player.STOP_DOWNLOADS"

        fun ensureStarted(context: Context) {
            if (OfflineDownloader.backgroundPaused || OfflineDownloader.pendingCount() == 0) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadForegroundService::class.java))
                context.getSystemService(NotificationManager::class.java).cancel(PAUSED_ID)
            } catch (e: RuntimeException) {
                // Background-start restrictions must not discard a saved playlist queue.
                Log.w(TAG, "Waiting for the player to open: ${e.javaClass.simpleName}")
                OfflineDownloader.pauseForSystem()
            }
        }
    }
}
