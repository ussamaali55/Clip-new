package com.clipgenius.ai.cutter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.clipgenius.ai.ClipGeniusApplication
import com.clipgenius.ai.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground Service for background and sequential video cutting.
 * Displays persistent notification: "Cutting clips: 3 of 8" with progress.
 */
class ClipCuttingService : Service() {

    companion object {
        private const val TAG = "ClipCuttingService"
        const val CHANNEL_ID = "clip_cutting_channel"
        const val NOTIFICATION_ID = 4001

        const val ACTION_CUT_ALL = "com.clipgenius.ai.action.CUT_ALL"
        const val ACTION_CUT_SINGLE = "com.clipgenius.ai.action.CUT_SINGLE"

        const val EXTRA_PROJECT_ID = "extra_project_id"
        const val EXTRA_CLIP_ID = "extra_clip_id"
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val projectId = intent?.getStringExtra(EXTRA_PROJECT_ID)

        if (projectId.isNullOrBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Start foreground with initial notification
        val initialNotification = buildNotification("Clip Genius Video Cutter", "Preparing cutting queue...", 0, 100)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
            } else {
                startForeground(NOTIFICATION_ID, initialNotification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground error", e)
        }

        val app = applicationContext as ClipGeniusApplication
        val repository = app.projectRepository

        serviceScope.launch {
            try {
                when (action) {
                    ACTION_CUT_ALL -> {
                        ClipCutterManager.cutAllVerified(
                            context = applicationContext,
                            projectId = projectId,
                            repository = repository,
                            onNotificationUpdate = { title, message, progress, max ->
                                updateNotification(title, message, progress, max)
                            }
                        )
                    }
                    ACTION_CUT_SINGLE -> {
                        val clipId = intent.getStringExtra(EXTRA_CLIP_ID)
                        if (!clipId.isNullOrBlank()) {
                            ClipCutterManager.cutSingleClip(
                                context = applicationContext,
                                projectId = projectId,
                                clipId = clipId,
                                repository = repository,
                                onNotificationUpdate = { title, message, progress, max ->
                                    updateNotification(title, message, progress, max)
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cutting service task failed", e)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Clip Cutting Engine",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground notifications for video clip slicing"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(title: String, message: String, progress: Int, max: Int): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setProgress(max, progress, progress == 0 && max == 100)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(title: String, message: String, progress: Int, max: Int) {
        val notification = buildNotification(title, message, progress, max)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
