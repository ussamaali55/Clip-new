package com.clipgenius.ai.cutter

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.clipgenius.ai.data.ProjectRepository
import com.clipgenius.ai.state.CutStatus
import com.clipgenius.ai.state.Project
import com.clipgenius.ai.state.VerificationStatus
import com.clipgenius.ai.state.VerifiedClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Coordinates background and foreground cutting queues for Clip Genius AI.
 * Handles storage guards, sequential execution, per-clip retries, and persistence in Room.
 */
object ClipCutterManager {

    private const val TAG = "ClipCutterManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isCuttingQueue = MutableStateFlow(false)
    val isCuttingQueue: StateFlow<Boolean> = _isCuttingQueue.asStateFlow()

    private val _activeCuttingClipId = MutableStateFlow<String?>(null)
    val activeCuttingClipId: StateFlow<String?> = _activeCuttingClipId.asStateFlow()

    private val _activeClipProgress = MutableStateFlow(0f)
    val activeClipProgress: StateFlow<Float> = _activeClipProgress.asStateFlow()

    private val _queueStatusText = MutableStateFlow<String?>(null)
    val queueStatusText: StateFlow<String?> = _queueStatusText.asStateFlow()

    private val _storageError = MutableStateFlow<String?>(null)
    val storageError: StateFlow<String?> = _storageError.asStateFlow()

    fun clearStorageError() {
        _storageError.value = null
    }

    /**
     * Starts the Foreground Service to process all verified clips sequentially.
     */
    fun startCutAllForeground(context: Context, projectId: String) {
        val intent = Intent(context, ClipCuttingService::class.java).apply {
            action = ClipCuttingService.ACTION_CUT_ALL
            putExtra(ClipCuttingService.EXTRA_PROJECT_ID, projectId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Starts the Foreground Service to process a single verified clip.
     */
    fun startCutSingleForeground(context: Context, projectId: String, clipId: String) {
        val intent = Intent(context, ClipCuttingService::class.java).apply {
            action = ClipCuttingService.ACTION_CUT_SINGLE
            putExtra(ClipCuttingService.EXTRA_PROJECT_ID, projectId)
            putExtra(ClipCuttingService.EXTRA_CLIP_ID, clipId)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    /**
     * Cuts a single verified clip.
     */
    suspend fun cutSingleClip(
        context: Context,
        projectId: String,
        clipId: String,
        repository: ProjectRepository,
        onNotificationUpdate: ((title: String, message: String, progress: Int, max: Int) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val project = repository.getProjectById(projectId) ?: return@withContext false
        val clip = project.verifiedClips.find { it.clipId == clipId } ?: return@withContext false
        val sourceMedia = project.sourceMedia ?: return@withContext false

        // Storage Guard
        val storageCheck = ClipCutter.checkStorageSpace(context, listOf(clip), sourceMedia)
        if (storageCheck is ClipCutter.StorageCheckResult.InsufficientStorage) {
            _storageError.value = storageCheck.message
            return@withContext false
        } else if (storageCheck is ClipCutter.StorageCheckResult.Error) {
            _storageError.value = storageCheck.message
            return@withContext false
        }

        _activeCuttingClipId.value = clipId
        _activeClipProgress.value = 0f
        _queueStatusText.value = "Cutting clip 1 of 1..."
        onNotificationUpdate?.invoke("Clip Genius Video Cutter", "Cutting clip: 1 of 1", 10, 100)

        // Mark clip CUTTING
        updateClipInProject(repository, projectId, clipId) {
            it.copy(cutStatus = CutStatus.CUTTING, cutProgress = 0.05f, cutErrorMessage = null)
        }

        val cutResult = ClipCutter.cutClip(
            context = context,
            projectId = projectId,
            clip = clip,
            sourceMedia = sourceMedia,
            onProgress = { progress ->
                _activeClipProgress.value = progress
                onNotificationUpdate?.invoke(
                    "Clip Genius Video Cutter",
                    "Cutting clip: 1 of 1 (${(progress * 100).toInt()}%)",
                    (progress * 100).toInt(),
                    100
                )
            }
        )

        val success = cutResult.success && cutResult.qcPassed
        updateClipInProject(repository, projectId, clipId) {
            if (success) {
                it.copy(
                    cutStatus = CutStatus.DONE,
                    draftVideoPath = cutResult.outputPath,
                    cutProgress = 1f,
                    qcPassed = true,
                    qcDetails = cutResult.qcDetails,
                    cutDurationMs = cutResult.actualDurationMs,
                    cutFileSizeBytes = cutResult.fileSizeBytes,
                    cutErrorMessage = null
                )
            } else {
                it.copy(
                    cutStatus = CutStatus.ERROR,
                    draftVideoPath = cutResult.outputPath,
                    cutProgress = 0f,
                    qcPassed = false,
                    cutErrorMessage = cutResult.errorMessage ?: "Cutting failed QC verification."
                )
            }
        }

        _activeCuttingClipId.value = null
        _activeClipProgress.value = 0f
        _queueStatusText.value = null
        success
    }

    /**
     * Processes all eligible clips sequentially in a queue.
     * Per-clip retry on failure; failures never delete already-cut clips.
     */
    suspend fun cutAllVerified(
        context: Context,
        projectId: String,
        repository: ProjectRepository,
        onNotificationUpdate: ((title: String, message: String, progress: Int, max: Int) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val project = repository.getProjectById(projectId) ?: return@withContext false
        val sourceMedia = project.sourceMedia ?: return@withContext false

        // Eligible clips: VERIFIED status or manually edited, included in export
        val eligibleClips = project.verifiedClips.filter {
            (it.status == VerificationStatus.VERIFIED || it.isManuallyEdited) &&
            it.isIncludedInExport &&
            it.cutStatus != CutStatus.DONE
        }

        if (eligibleClips.isEmpty()) {
            Log.i(TAG, "No clips need cutting.")
            return@withContext true
        }

        // Storage Guard Check
        val storageCheck = ClipCutter.checkStorageSpace(context, eligibleClips, sourceMedia)
        if (storageCheck is ClipCutter.StorageCheckResult.InsufficientStorage) {
            _storageError.value = storageCheck.message
            return@withContext false
        } else if (storageCheck is ClipCutter.StorageCheckResult.Error) {
            _storageError.value = storageCheck.message
            return@withContext false
        }

        _isCuttingQueue.value = true
        val totalCount = eligibleClips.size

        for ((index, clip) in eligibleClips.withIndex()) {
            val clipNum = index + 1
            val statusMessage = "Cutting clips: $clipNum of $totalCount"
            _queueStatusText.value = statusMessage
            _activeCuttingClipId.value = clip.clipId
            _activeClipProgress.value = 0f

            onNotificationUpdate?.invoke(
                "Clip Genius Video Cutter",
                statusMessage,
                ((clipNum - 1) * 100) / totalCount,
                100
            )

            // Mark this clip CUTTING
            updateClipInProject(repository, projectId, clip.clipId) {
                it.copy(cutStatus = CutStatus.CUTTING, cutProgress = 0.05f, cutErrorMessage = null)
            }

            try {
                val cutResult = ClipCutter.cutClip(
                    context = context,
                    projectId = projectId,
                    clip = clip,
                    sourceMedia = sourceMedia,
                    onProgress = { progress ->
                        _activeClipProgress.value = progress
                        val overallPercentage = (((index + progress) / totalCount.toFloat()) * 100).toInt()
                        onNotificationUpdate?.invoke(
                            "Clip Genius Video Cutter",
                            "Cutting clips: $clipNum of $totalCount (${(progress * 100).toInt()}%)",
                            overallPercentage,
                            100
                        )
                    }
                )

                val success = cutResult.success && cutResult.qcPassed
                updateClipInProject(repository, projectId, clip.clipId) {
                    if (success) {
                        it.copy(
                            cutStatus = CutStatus.DONE,
                            draftVideoPath = cutResult.outputPath,
                            cutProgress = 1f,
                            qcPassed = true,
                            qcDetails = cutResult.qcDetails,
                            cutDurationMs = cutResult.actualDurationMs,
                            cutFileSizeBytes = cutResult.fileSizeBytes,
                            cutErrorMessage = null
                        )
                    } else {
                        it.copy(
                            cutStatus = CutStatus.ERROR,
                            draftVideoPath = cutResult.outputPath,
                            cutProgress = 0f,
                            qcPassed = false,
                            cutErrorMessage = cutResult.errorMessage ?: "Cutting failed QC check."
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error cutting clip in queue: ${clip.clipId}", e)
                updateClipInProject(repository, projectId, clip.clipId) {
                    it.copy(
                        cutStatus = CutStatus.ERROR,
                        cutProgress = 0f,
                        qcPassed = false,
                        cutErrorMessage = e.message ?: "Cutting failed."
                    )
                }
            }
        }

        _isCuttingQueue.value = false
        _activeCuttingClipId.value = null
        _activeClipProgress.value = 0f
        _queueStatusText.value = null
        onNotificationUpdate?.invoke("Clip Genius Video Cutter", "All cuts finished!", 100, 100)
        true
    }

    /**
     * Deletes a draft clip MP4 to free disk storage while keeping the VerifiedClip record.
     */
    suspend fun deleteDraftClip(
        context: Context,
        projectId: String,
        clipId: String,
        repository: ProjectRepository
    ): Boolean = withContext(Dispatchers.IO) {
        ClipCutter.deleteDraft(context, projectId, clipId)
        updateClipInProject(repository, projectId, clipId) {
            it.copy(
                cutStatus = CutStatus.NOT_CUT,
                draftVideoPath = null,
                cutProgress = 0f,
                qcPassed = false,
                qcDetails = null,
                cutDurationMs = null,
                cutFileSizeBytes = null,
                cutErrorMessage = null
            )
        }
        true
    }

    private suspend fun updateClipInProject(
        repository: ProjectRepository,
        projectId: String,
        clipId: String,
        transform: (VerifiedClip) -> VerifiedClip
    ) {
        val currentProject = repository.getProjectById(projectId) ?: return
        val updatedClips = currentProject.verifiedClips.map {
            if (it.clipId == clipId) transform(it) else it
        }
        val updatedProject = currentProject.copy(verifiedClips = updatedClips)
        repository.saveProject(updatedProject)
    }
}
