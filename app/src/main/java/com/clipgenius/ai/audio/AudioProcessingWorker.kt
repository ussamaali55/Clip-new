package com.clipgenius.ai.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.clipgenius.ai.R
import com.clipgenius.ai.data.ClipGeniusDatabase
import com.clipgenius.ai.data.ProjectRepository
import com.clipgenius.ai.state.SourceMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkManager worker for background audio extraction & chunking with persistent status notification.
 */
class AudioProcessingWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val CHANNEL_ID = "clip_genius_audio_channel"
        const val NOTIFICATION_ID = 1001
        const val KEY_PROJECT_ID = "project_id"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val projectId = inputData.getString(KEY_PROJECT_ID) ?: return@withContext Result.failure()

        val database = ClipGeniusDatabase.getDatabase(appContext)
        val repository = ProjectRepository(database.projectDao())

        val project = repository.getProjectById(projectId) ?: return@withContext Result.failure()
        val sourceMedia = project.sourceMedia ?: return@withContext Result.failure()

        createNotificationChannel()
        setForeground(createForegroundInfo("Preparing audio: 0%"))

        try {
            val manifest = AudioExtractor.extractAndChunkAudio(
                context = appContext,
                projectId = projectId,
                sourceMedia = sourceMedia,
                onProgress = { progress, message ->
                    val percentage = (progress * 100).toInt()
                    setForegroundAsync(createForegroundInfo("Preparing audio: $percentage% - $message"))
                }
            )

            val updatedProject = project.copy(
                audioManifest = manifest
            )
            repository.saveProject(updatedProject)

            return@withContext Result.success()
        } catch (e: Exception) {
            return@withContext Result.failure()
        }
    }

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle("Clip Genius AI")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Processing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress during video audio extraction and chunking"
            }
            val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}
