package com.clipgenius.ai.render

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.clipgenius.ai.captions.CaptionGenerator
import com.clipgenius.ai.data.ProjectRepository
import com.clipgenius.ai.state.ActivityLogEntry
import com.clipgenius.ai.state.CaptionCue
import com.clipgenius.ai.state.CaptionPreset
import com.clipgenius.ai.state.LayoutType
import com.clipgenius.ai.state.Project
import com.clipgenius.ai.state.RenderStatus
import com.clipgenius.ai.state.VerificationStatus
import com.clipgenius.ai.state.VerifiedClip
import com.clipgenius.ai.tracking.CropPlanner
import com.clipgenius.ai.tracking.TrackingManager
import kotlinx.coroutines.CompletableDeferred
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
 * Phase 12 Final Video Exporter.
 * Executes ONE single high-fidelity FFmpeg render pass combining:
 * Sliced Segment → 9:16 Reframe Layout → Effects Look / Mirror / Zoom → Burned-in ASS Subtitles.
 * Encodes to libx264 (crf 18, preset medium), AAC 128k, validates against 7-point QC,
 * saves sidecar metadata (.txt), and registers the video into the device's Movies/ClipGenius gallery.
 */
object FinalExporter {

    private const val TAG = "FinalExporter"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _activeClipId = MutableStateFlow<String?>(null)
    val activeClipId: StateFlow<String?> = _activeClipId.asStateFlow()

    private val _exportProgress = MutableStateFlow(0f)
    val exportProgress: StateFlow<Float> = _exportProgress.asStateFlow()

    private val _exportStatusText = MutableStateFlow<String?>("")
    val exportStatusText: StateFlow<String?> = _exportStatusText.asStateFlow()

    private const val NOTIFICATION_CHANNEL_ID = "clip_export_channel"
    private const val NOTIFICATION_ID = 4002

    private fun showNotification(
        context: Context,
        title: String,
        content: String,
        progress: Int,
        max: Int
    ) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Clip Genius Video Export",
                    NotificationManager.IMPORTANCE_LOW
                )
                nm.createNotificationChannel(channel)
            }
            val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(content)
                .setOngoing(max > 0 && progress < max)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            if (max > 0) {
                builder.setProgress(max, progress, false)
            }
            nm.notify(NOTIFICATION_ID, builder.build())
        } catch (_: Exception) {}
    }

    fun getExportFile(context: Context, projectId: String, clipId: String): File {
        val dir = File(context.filesDir, "projects/$projectId/exports")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${clipId}_final.mp4")
    }

    fun getMetadataFile(context: Context, projectId: String, clipId: String): File {
        val dir = File(context.filesDir, "projects/$projectId/exports")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "${clipId}_metadata.txt")
    }

    /**
     * Sequentially exports all verified & included clips for a project.
     * Supports resumeOnly to skip already-rendered QC-passed clips.
     */
    fun exportAllClips(
        context: Context,
        projectId: String,
        repository: ProjectRepository,
        resumeOnly: Boolean = false
    ) {
        scope.launch {
            if (_isExporting.value) return@launch
            _isExporting.value = true

            try {
                val project = repository.getProjectById(projectId) ?: return@launch
                val allClips = project.verifiedClips.filter {
                    (it.status == VerificationStatus.VERIFIED || it.isManuallyEdited) && it.isIncludedInExport
                }

                val clips = if (resumeOnly) {
                    allClips.filter { it.finalExportStatus != RenderStatus.COMPLETE || !it.finalQcPassed }
                } else {
                    allClips
                }

                logActivity(
                    repository,
                    projectId,
                    "Render",
                    if (resumeOnly) "Resume Export Queue" else "Export All Queue",
                    "INFO",
                    "Starting sequential render of ${clips.size} clips"
                )

                showNotification(
                    context,
                    "Clip Genius AI — Exporting Videos",
                    "Starting render queue (${clips.size} clips)...",
                    0,
                    100
                )

                for ((idx, clip) in clips.withIndex()) {
                    _exportStatusText.value = "Exporting clip ${idx + 1} of ${clips.size}: ${clip.originalCandidate.title}"
                    showNotification(
                        context,
                        "Exporting Clip ${idx + 1} of ${clips.size}",
                        clip.originalCandidate.title,
                        ((idx.toFloat() / clips.size.toFloat()) * 100).toInt(),
                        100
                    )
                    renderSingleClip(context, projectId, clip.clipId, repository)
                }

                showNotification(
                    context,
                    "Clip Genius AI — Export Complete",
                    "All clips exported to Movies/ClipGenius with full broadcast QC",
                    0,
                    0
                )

                // Check and mark stage complete
                checkAndUpdateRenderStageCompletion(projectId, repository)
            } finally {
                _isExporting.value = false
                _activeClipId.value = null
                _exportProgress.value = 0f
                _exportStatusText.value = null
            }
        }
    }

    /**
     * Renders an individual clip with all framing, effects, subtitles, and runs QC.
     */
    suspend fun renderSingleClip(
        context: Context,
        projectId: String,
        clipId: String,
        repository: ProjectRepository
    ): Boolean = withContext(Dispatchers.IO) {
        val project = repository.getProjectById(projectId) ?: return@withContext false
        val clip = project.verifiedClips.find { it.clipId == clipId } ?: return@withContext false

        // Determine source video
        val inputPath = clip.draftVideoPath ?: clip.verticalDraftPath ?: project.sourceVideoPath
        val inputFile = File(inputPath)
        if (!inputFile.exists()) {
            updateClip(repository, projectId, clipId) {
                it.copy(
                    finalExportStatus = RenderStatus.ERROR,
                    finalExportErrorMessage = "Source video draft not found. Please slice or verify clip first."
                )
            }
            logActivity(repository, projectId, "Render", "Export Clip", "FAILED", "Clip $clipId: Input video not found")
            return@withContext false
        }

        _activeClipId.value = clipId
        _exportProgress.value = 0.05f
        _exportStatusText.value = "Preparing filters & subtitles for '${clip.originalCandidate.title}'..."

        updateClip(repository, projectId, clipId) {
            it.copy(finalExportStatus = RenderStatus.PROCESSING, finalExportProgress = 0.05f, finalExportErrorMessage = null)
        }

        val outputFile = getExportFile(context, projectId, clipId)
        if (outputFile.exists()) outputFile.delete()

        val expectedDurMs = clip.cutDurationMs ?: clip.verifiedRanges.sumOf { it.endMs - it.startMs }

        // 1. Framing / Layout Filter
        val segments = project.transcript?.segments ?: emptyList()
        val cropPlan = TrackingManager.getOrComputeCropPlan(context, projectId, clip, segments)
        val layoutPlan = TrackingManager.getOrComputeLayoutPlan(context, projectId, clip, segments)

        val isMultiPerson = layoutPlan.layoutType != LayoutType.SINGLE &&
            layoutPlan.layoutType != LayoutType.SINGLE_SPEAKER_FOLLOW &&
            layoutPlan.panels.size > 1

        // 2. Post-processing (Zoom reframe, mirror, color look)
        val postProcess = EffectsEngine.buildPostProcessingFilterChain(
            selectedFilter = clip.selectedFilter,
            isMirrored = clip.isMirrored,
            manualReframeOffsetX = clip.manualReframeOffsetX,
            manualReframeZoom = clip.manualReframeZoom
        )

        // 3. Subtitles ASS file generation
        var assFilter = ""
        var loadedCues = emptyList<CaptionCue>()
        var loadedPreset: CaptionPreset? = null

        val captionsFile = CaptionGenerator.getCaptionsFile(context, projectId, clipId)
        if (captionsFile.exists()) {
            loadedCues = CaptionGenerator.loadCaptionsFromDisk(captionsFile)
        }
        val pId = clip.captionPresetId ?: CaptionGenerator.PRESET_BOLD_TIKTOK.id
        loadedPreset = CaptionGenerator.BUILT_IN_PRESETS.find { p -> p.id == pId } ?: CaptionGenerator.PRESET_BOLD_TIKTOK

        if (clip.burnCaptions && loadedCues.isNotEmpty()) {
            val assFile = File(context.filesDir, "projects/$projectId/captions/${clipId}_subtitles.ass")
            SubtitleAssGenerator.generateAssFile(assFile, loadedCues, loadedPreset)
            // Escape path for ffmpeg filter
            val escapedPath = assFile.absolutePath.replace("\\", "/").replace(":", "\\:").replace("'", "\\'")
            assFilter = "subtitles='$escapedPath'"
        }

        // Determine if direct seeking from source is needed for one single pass
        val isCutFromSource = inputFile.absolutePath == project.sourceVideoPath && (clip.draftVideoPath == null || !File(clip.draftVideoPath).exists())
        val range = clip.verifiedRanges.firstOrNull()
        val seekArg = if (isCutFromSource && range != null) {
            "-ss ${range.startMs}ms -to ${range.endMs}ms "
        } else {
            ""
        }

        // Build command
        val command = if (isMultiPerson) {
            val filterComplex = buildMultiPersonWithPostProcess(layoutPlan, postProcess, assFilter)
            "-y $seekArg-i \"${inputFile.absolutePath}\" -filter_complex \"$filterComplex\" -map \"[outv]\" -map 0:a? -c:v libx264 -preset medium -crf 18 -c:a aac -b:a 128k \"${outputFile.absolutePath}\""
        } else {
            val vfParts = mutableListOf<String>()
            val baseCrop = CropPlanner.buildFfmpegCropFilter(cropPlan)
            vfParts.add(baseCrop)
            if (postProcess.isNotBlank()) vfParts.add(postProcess)
            if (assFilter.isNotBlank()) vfParts.add(assFilter)
            val vfString = vfParts.joinToString(",")
            "-y $seekArg-i \"${inputFile.absolutePath}\" -vf \"$vfString\" -c:v libx264 -preset medium -crf 18 -c:a aac -b:a 128k \"${outputFile.absolutePath}\""
        }

        Log.i(TAG, "Executing Final Render FFmpeg command: $command")
        val deferred = CompletableDeferred<Boolean>()

        try {
            FFmpegKit.executeAsync(
                command,
                { session ->
                    val success = ReturnCode.isSuccess(session.returnCode)
                    if (!success) {
                        Log.e(TAG, "Final render FFmpeg failed: ${session.failStackTrace}")
                    }
                    deferred.complete(success)
                },
                { /* log */ },
                { stats ->
                    if (expectedDurMs > 0) {
                        val prog = (stats.time.toFloat() / expectedDurMs.toFloat()).coerceIn(0.05f, 0.95f)
                        _exportProgress.value = prog
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch render session", e)
            updateClip(repository, projectId, clipId) {
                it.copy(finalExportStatus = RenderStatus.ERROR, finalExportErrorMessage = e.message)
            }
            logActivity(repository, projectId, "Render", "Export Clip", "FAILED", "Clip $clipId exception: ${e.message}")
            return@withContext false
        }

        val renderSuccess = deferred.await()
        _exportProgress.value = 0.98f

        if (!renderSuccess || !outputFile.exists() || outputFile.length() == 0L) {
            updateClip(repository, projectId, clipId) {
                it.copy(
                    finalExportStatus = RenderStatus.ERROR,
                    finalExportErrorMessage = "Render failed. Output video file was not generated."
                )
            }
            logActivity(repository, projectId, "Render", "Export Clip", "FAILED", "Clip $clipId: FFmpeg process failed")
            return@withContext false
        }

        // 4. Run QC Checklist
        _exportStatusText.value = "Running 7-point Broadcast QC Verification..."
        val qcResult = QualityChecklist.runFullQc(context, outputFile, expectedDurMs, clip.burnCaptions, loadedCues, loadedPreset)

        if (!qcResult.passed) {
            val failReasons = qcResult.checks.filter { !it.passed }.map { "${it.title}: ${it.detail}" }
            updateClip(repository, projectId, clipId) {
                it.copy(
                    finalExportStatus = RenderStatus.ERROR,
                    finalQcPassed = false,
                    finalQcDetails = failReasons,
                    finalExportErrorMessage = "QC checks failed: " + failReasons.joinToString("; ")
                )
            }
            logActivity(repository, projectId, "Render", "QC Check", "FAILED", "Clip $clipId failed QC: ${failReasons.joinToString()}")
            return@withContext false
        }

        // 5. Generate Sidecar .txt metadata
        val metadataFile = getMetadataFile(context, projectId, clipId)
        val hashtagsStr = if (clip.originalCandidate.hashtags.isNotEmpty()) {
            clip.originalCandidate.hashtags.joinToString(" ") { if (it.startsWith("#")) it else "#$it" }
        } else {
            "#shorts #viral #fyp"
        }
        val metadataText = """
            Title:
            ${clip.originalCandidate.title}
            
            Caption:
            ${clip.originalCandidate.caption.ifBlank { clip.originalCandidate.hookSentence }}
            
            Hashtags:
            $hashtagsStr
        """.trimIndent()
        metadataFile.writeText(metadataText)

        // 6. Copy to Phone's Movies/ClipGenius Gallery
        val galleryUri = copyToGallery(context, outputFile, clip.originalCandidate.title)

        updateClip(repository, projectId, clipId) {
            it.copy(
                finalExportStatus = RenderStatus.COMPLETE,
                finalExportPath = outputFile.absolutePath,
                finalExportProgress = 1.0f,
                finalQcPassed = true,
                finalQcDetails = qcResult.checks.map { c -> "${c.title}: ${c.detail}" },
                mediaStoreUri = galleryUri,
                metadataTxtPath = metadataFile.absolutePath,
                finalExportErrorMessage = null
            )
        }

        logActivity(
            repository,
            projectId,
            "Render",
            "Export Clip",
            "SUCCESS",
            "Clip '${clip.originalCandidate.title}' exported successfully to gallery with all 7 QC checks passed"
        )
        true
    }

    private fun buildMultiPersonWithPostProcess(
        layoutPlan: com.clipgenius.ai.state.ClipLayoutPlan,
        postProcess: String,
        assFilter: String
    ): String {
        val panels = layoutPlan.panels
        val sb = StringBuilder()

        when (layoutPlan.layoutType) {
            LayoutType.TWO_PERSON_STACKED -> {
                val crop0 = CropPlanner.buildFfmpegCropFilter(panels[0].cropPlan)
                val crop1 = CropPlanner.buildFfmpegCropFilter(panels[1].cropPlan)
                sb.append("[0:v]split=2[in0][in1];")
                sb.append("[in0]$crop0,scale=1080:960:flags=bicubic[top];")
                sb.append("[in1]$crop1,scale=1080:960:flags=bicubic[bottom];")
                sb.append("[top][bottom]vstack=inputs=2[stacked];")
                sb.append("[stacked]drawbox=y=958:h=4:color=white@0.6:t=fill[bordered]")
            }
            LayoutType.THREE_PERSON_GRID -> {
                val crop0 = CropPlanner.buildFfmpegCropFilter(panels[0].cropPlan)
                val crop1 = CropPlanner.buildFfmpegCropFilter(panels[1].cropPlan)
                val crop2 = CropPlanner.buildFfmpegCropFilter(panels[2].cropPlan)
                sb.append("[0:v]split=3[in0][in1][in2];")
                sb.append("[in0]$crop0,scale=540:960:flags=bicubic[tl];")
                sb.append("[in1]$crop1,scale=540:960:flags=bicubic[tr];")
                sb.append("[in2]$crop2,scale=1080:960:flags=bicubic[b];")
                sb.append("[tl][tr]hstack=inputs=2[toprow];")
                sb.append("[toprow][b]vstack=inputs=2[bordered]")
            }
            LayoutType.FOUR_PERSON_GRID -> {
                val crop0 = CropPlanner.buildFfmpegCropFilter(panels[0].cropPlan)
                val crop1 = CropPlanner.buildFfmpegCropFilter(panels[1].cropPlan)
                val crop2 = CropPlanner.buildFfmpegCropFilter(panels[2].cropPlan)
                val crop3 = CropPlanner.buildFfmpegCropFilter(panels[3].cropPlan)
                sb.append("[0:v]split=4[in0][in1][in2][in3];")
                sb.append("[in0]$crop0,scale=540:960:flags=bicubic[tl];")
                sb.append("[in1]$crop1,scale=540:960:flags=bicubic[tr];")
                sb.append("[in2]$crop2,scale=540:960:flags=bicubic[bl];")
                sb.append("[in3]$crop3,scale=540:960:flags=bicubic[br];")
                sb.append("[tl][tr]hstack=inputs=2[toprow];")
                sb.append("[bl][br]hstack=inputs=2[botrow];")
                sb.append("[toprow][botrow]vstack=inputs=2[bordered]")
            }
            else -> {
                sb.append("[0:v]scale=1080:1920[bordered]")
            }
        }

        val chain = mutableListOf<String>()
        if (postProcess.isNotBlank()) chain.add(postProcess)
        if (assFilter.isNotBlank()) chain.add(assFilter)

        if (chain.isNotEmpty()) {
            sb.append(";[bordered]${chain.joinToString(",")}[outv]")
        } else {
            sb.append(";[bordered]null[outv]")
        }

        return sb.toString()
    }

    /**
     * Saves the rendered video into Android's public Movies/ClipGenius gallery directory.
     */
    private fun copyToGallery(context: Context, videoFile: File, title: String): String? {
        return try {
            val resolver = context.contentResolver
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "ClipGenius_${System.currentTimeMillis()}.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.TITLE, title)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ClipGenius")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues) ?: return null
            resolver.openOutputStream(uri)?.use { out ->
                videoFile.inputStream().use { input ->
                    input.copyTo(out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }
            uri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy to gallery", e)
            null
        }
    }

    private suspend fun updateClip(
        repository: ProjectRepository,
        projectId: String,
        clipId: String,
        transform: (VerifiedClip) -> VerifiedClip
    ) {
        val current = repository.getProjectById(projectId) ?: return
        val updated = current.verifiedClips.map {
            if (it.clipId == clipId) transform(it) else it
        }
        repository.saveProject(current.copy(verifiedClips = updated))
    }

    private suspend fun logActivity(
        repository: ProjectRepository,
        projectId: String,
        stage: String,
        operation: String,
        status: String,
        details: String
    ) {
        val current = repository.getProjectById(projectId) ?: return
        val entry = ActivityLogEntry(
            stage = stage,
            operation = operation,
            status = status,
            details = details
        )
        val updatedLogs = current.activityLogs + entry
        repository.saveProject(current.copy(activityLogs = updatedLogs))
    }

    private suspend fun checkAndUpdateRenderStageCompletion(
        projectId: String,
        repository: ProjectRepository
    ) {
        val project = repository.getProjectById(projectId) ?: return
        val eligible = project.verifiedClips.filter {
            (it.status == VerificationStatus.VERIFIED || it.isManuallyEdited) && it.isIncludedInExport
        }
        val allCompleted = eligible.isNotEmpty() && eligible.all { it.finalExportStatus == RenderStatus.COMPLETE && it.finalQcPassed }
        if (allCompleted) {
            val updatedStages = project.stages.toMutableMap().apply {
                put("Render", "completed")
                put("Complete", "completed")
            }
            repository.saveProject(project.copy(stages = updatedStages))
        }
    }
}
