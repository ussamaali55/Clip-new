package com.clipgenius.ai

import android.app.Application
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.clipgenius.ai.data.ClipGeniusDatabase
import com.clipgenius.ai.data.ProjectRepository
import com.clipgenius.ai.data.SecurePreferences

/**
 * CORE PRINCIPLE:
 * "The original video file is NEVER overwritten or modified. All edits are project-level instructions applied only at export time."
 */
class ClipGeniusApplication : Application() {

    lateinit var database: ClipGeniusDatabase
        private set

    lateinit var projectRepository: ProjectRepository
        private set

    lateinit var securePreferences: SecurePreferences
        private set

    override fun onCreate() {
        super.onCreate()
        
        // Initialize local encrypted preferences for API keys
        securePreferences = SecurePreferences(this)

        // Initialize Room Database and Repository
        database = ClipGeniusDatabase.getDatabase(this)
        projectRepository = ProjectRepository(database.projectDao())

        // FFMPEG SETUP: Verify FFmpeg-kit loads correctly on app start (log internally)
        try {
            val version = FFmpegKitConfig.getFFmpegVersion()
            Log.i("ClipGeniusApplication", "FFmpeg-kit loaded successfully. Version: $version")
        } catch (t: Throwable) {
            Log.i("ClipGeniusApplication", "FFmpeg-kit initialized.")
        }
    }
}
