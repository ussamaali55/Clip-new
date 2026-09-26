package com.clipgenius.ai.pipeline

import android.content.Context
import android.util.Log
import com.clipgenius.ai.aiplanner.AiClipPlanner
import com.clipgenius.ai.audio.AudioExtractor
import com.clipgenius.ai.data.ProjectRepository
import com.clipgenius.ai.data.SecurePreferences
import com.clipgenius.ai.state.*
import com.clipgenius.ai.transcription.DeepgramAuthException
import com.clipgenius.ai.transcription.DeepgramClient
import com.clipgenius.ai.transcription.DeepgramQuotaException
import com.clipgenius.ai.transcription.TranscriptMerger
import com.clipgenius.ai.transcription.WhisperOfflineFallbackTranscriber
import com.clipgenius.ai.verification.TimestampVerifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

data class PipelineProgress(
    val projectId: String? = null,
    val stage: ProjectStage = ProjectStage.IMPORTED,
    val stageName: String = "Imported",
    val percentage: Float = 0f,
    val statusText: String = "Ready",
    val isRunning: Boolean = false,
    val error: String? = null,
    val canRetry: Boolean = false,
    val isCompleted: Boolean = false
)

/**
 * Central PipelineOrchestrator that automates backend processing across stages:
 * Audio Extraction → Transcription (Deepgram; Whisper offline fallback) → Gemini Clip Discovery → Timestamp Verification → Ready for Editing.
 * Guarantees single active project, persists state on every transition, and supports resume-from-failure.
 */
object PipelineOrchestrator {

    private const val TAG = "PipelineOrchestrator"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeJob: Job? = null

    private val _activeProjectId = MutableStateFlow<String?>(null)
    val activeProjectId: StateFlow<String?> = _activeProjectId.asStateFlow()

    private val _progress = MutableStateFlow(PipelineProgress())
    val progress: StateFlow<PipelineProgress> = _progress.asStateFlow()

    /**
     * Starts or resumes the automated pipeline for the specified project.
     * Enforces single active project: any previous active project is frozen and saved.
     */
    fun startOrResume(context: Context, repository: ProjectRepository, projectId: String) {
        // Enforce Single Active Project
        val previousActiveId = _activeProjectId.value
        if (previousActiveId != null && previousActiveId != projectId) {
            freezeActiveProject(context, repository, previousActiveId)
        }

        _activeProjectId.value = projectId
        activeJob?.cancel()

        _progress.value = PipelineProgress(
            projectId = projectId,
            isRunning = true,
            statusText = "Initializing automated pipeline...",
            percentage = 0.05f
        )

        activeJob = scope.launch {
            try {
                // Mark active in database and freeze others
                repository.activateProject(projectId)

                var project = repository.getProjectById(projectId)
                    ?: throw IllegalStateException("Project $projectId not found in database.")

                val projectDir = File(context.filesDir, "projects/$projectId").apply { mkdirs() }
                val videoPath = if (project.sourceVideoPath.isNotBlank()) project.sourceVideoPath else project.videoUri
                val videoFile = File(videoPath)
                if (!videoFile.exists() || videoFile.length() <= 0) {
                    throw IllegalStateException("Source video file is missing or inaccessible at: $videoPath")
                }

                // ---------------------------------------------------------
                // STAGE 1: Audio Extraction
                // ---------------------------------------------------------
                var audioManifest = project.audioManifest
                if (audioManifest == null || !audioManifest.isExtractionComplete || audioManifest.chunks.isEmpty()) {
                    updateProgress(projectId, ProjectStage.IMPORTED, "Audio Extraction", 0.10f, "Extracting audio track from video...")

                    val media = project.sourceMedia ?: SourceMedia(
                        path = videoPath,
                        fileName = videoFile.name,
                        durationMs = 0L,
                        hasAudio = true
                    )

                    val manifest = AudioExtractor.extractAndChunkAudio(
                        context = context,
                        projectId = projectId,
                        sourceMedia = media,
                        onProgress = { p: Float, status: String ->
                            _progress.value = _progress.value.copy(
                                percentage = 0.10f + (p * 0.15f),
                                statusText = status
                            )
                        }
                    )
                    audioManifest = manifest

                    project = project.copy(
                        audioManifest = audioManifest,
                        currentStage = ProjectStage.AUDIO_EXTRACTED,
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.saveProject(project)
                    Log.i(TAG, "Stage 1 complete: Audio extracted with ${manifest.chunks.size} chunks.")
                }

                val currentManifest = audioManifest ?: project.audioManifest
                    ?: throw IllegalStateException("Audio extraction failed to produce manifest.")

                // ---------------------------------------------------------
                // STAGE 2: Speech-to-Text Transcription
                // ---------------------------------------------------------
                var transcript = project.transcript
                if (transcript == null || transcript.segments.isEmpty()) {
                    updateProgress(projectId, ProjectStage.TRANSCRIBING, "Transcription", 0.28f, "Transcribing speech into text...")

                    val securePrefs = SecurePreferences(context)
                    val deepgramKey = securePrefs.getDeepgramApiKey().trim()
                    val deepgramClient = DeepgramClient(context)
                    val whisperFallback = WhisperOfflineFallbackTranscriber(context)

                    val chunkSegmentsMap = mutableMapOf<Int, List<TranscriptSegment>>()
                    var usedFallback = false

                    for (chunk in currentManifest.chunks) {
                        // Check if chunk is already transcribed and cached on disk
                        val cached = TranscriptMerger.loadChunkTranscript(context, projectId, chunk.index)
                        if (cached != null && cached.isNotEmpty()) {
                            chunkSegmentsMap[chunk.index] = cached
                            continue
                        }

                        val chunkProgress = 0.28f + (0.30f * (chunk.index.toFloat() / currentManifest.chunks.size.coerceAtLeast(1)))
                        updateProgress(
                            projectId,
                            ProjectStage.TRANSCRIBING,
                            "Transcription",
                            chunkProgress,
                            "Transcribing chunk ${chunk.index + 1} of ${currentManifest.chunks.size}..."
                        )

                        var segments: List<TranscriptSegment>? = null
                        if (deepgramKey.isNotEmpty()) {
                            try {
                                segments = deepgramClient.transcribeChunk(chunk)
                            } catch (e: Exception) {
                                Log.w(TAG, "Deepgram failed for chunk ${chunk.index}: ${e.message}. Using Whisper offline fallback.")
                                usedFallback = true
                            }
                        }

                        if (segments == null) {
                            usedFallback = true
                            updateProgress(
                                projectId,
                                ProjectStage.TRANSCRIBING,
                                "Transcription",
                                chunkProgress,
                                "Transcribing chunk ${chunk.index + 1} via Whisper offline fallback..."
                            )
                            segments = whisperFallback.transcribeChunk(chunk)
                        }

                        chunkSegmentsMap[chunk.index] = segments
                        TranscriptMerger.saveChunkTranscript(context, projectId, chunk.index, segments)
                    }

                    val mergedTranscript = TranscriptMerger.mergeAndDeduplicate(
                        context = context,
                        projectId = projectId,
                        chunks = currentManifest.chunks,
                        chunkSegmentsMap = chunkSegmentsMap,
                        isFallbackGemini = usedFallback
                    )
                    transcript = mergedTranscript

                    project = project.copy(
                        transcript = transcript,
                        currentStage = ProjectStage.TRANSCRIBED,
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.saveProject(project)
                    Log.i(TAG, "Stage 2 complete: Transcribed ${mergedTranscript.segments.size} segments (total words: ${mergedTranscript.totalWords}).")
                }

                val currentTranscript = transcript ?: project.transcript
                    ?: throw IllegalStateException("Transcription failed to produce transcript.")

                // ---------------------------------------------------------
                // STAGE 3: Gemini AI Clip Discovery
                // ---------------------------------------------------------
                var candidates = project.clipCandidates
                if (candidates.isEmpty()) {
                    updateProgress(projectId, ProjectStage.DISCOVERING_CLIPS, "AI Clip Discovery", 0.62f, "Analyzing transcript with Gemini AI for viral moments...")

                    val aiPlanner = AiClipPlanner(context)
                    val discoveryResult = aiPlanner.discoverClips(
                        projectId = projectId,
                        transcript = currentTranscript,
                        videoDurationMs = project.sourceMedia?.durationMs ?: 0L,
                        onProgress = { pStatus ->
                            _progress.value = _progress.value.copy(statusText = pStatus)
                        }
                    )
                    candidates = discoveryResult.clips

                    if (candidates.isEmpty()) {
                        throw IllegalStateException("No viral clip candidates could be extracted from transcript. Please try with another video.")
                    }

                    project = project.copy(
                        clipCandidates = candidates,
                        currentStage = ProjectStage.DISCOVERING_CLIPS,
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.saveProject(project)
                    Log.i(TAG, "Stage 3 complete: Discovered ${candidates.size} clip candidates.")
                }

                // ---------------------------------------------------------
                // STAGE 4: Timestamp Verification
                // ---------------------------------------------------------
                var verifiedClips = project.verifiedClips
                if (verifiedClips.isEmpty()) {
                    updateProgress(projectId, ProjectStage.CLIPS_READY, "Timestamp Verification", 0.85f, "Verifying audio alignment and clip boundaries...")

                    val verifier = TimestampVerifier()
                    verifiedClips = verifier.verifyAll(
                        candidates = candidates,
                        transcript = currentTranscript,
                        videoDurationMs = project.sourceMedia?.durationMs ?: 0L
                    )

                    project = project.copy(
                        verifiedClips = verifiedClips,
                        currentStage = ProjectStage.CLIPS_READY,
                        updatedAt = System.currentTimeMillis()
                    )
                    repository.saveProject(project)
                    Log.i(TAG, "Stage 4 complete: Verified ${verifiedClips.size} clips.")
                }

                // ---------------------------------------------------------
                // PIPELINE COMPLETE: Ready for Editing
                // ---------------------------------------------------------
                _progress.value = PipelineProgress(
                    projectId = projectId,
                    stage = ProjectStage.CLIPS_READY,
                    stageName = "Clips Ready",
                    percentage = 1.0f,
                    statusText = "Pipeline complete! ${verifiedClips.size} clips ready for editing.",
                    isRunning = false,
                    isCompleted = true
                )
                Log.i(TAG, "Automated pipeline completed successfully for project: $projectId")

            } catch (e: CancellationException) {
                Log.i(TAG, "Pipeline cancelled or paused for project: $projectId")
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline failed at project $projectId: ${e.message}", e)
                val plainMessage = when {
                    e.message?.contains("key not found", ignoreCase = true) == true ->
                        "API key missing. Please enter your API key in Settings."
                    e.message?.contains("quota", ignoreCase = true) == true ->
                        "API quota exceeded. Please check your API credits."
                    e.message?.contains("video file is missing", ignoreCase = true) == true ->
                        "The original video file could not be found. Please re-import the video."
                    else -> e.localizedMessage ?: "Processing error occurred. Tap Retry to continue."
                }

                _progress.value = _progress.value.copy(
                    isRunning = false,
                    error = plainMessage,
                    canRetry = true
                )
            }
        }
    }

    /**
     * Pauses or freezes processing on a project, ensuring current state is persisted.
     */
    fun freezeActiveProject(context: Context, repository: ProjectRepository, projectId: String) {
        if (_activeProjectId.value == projectId) {
            activeJob?.cancel()
            _activeProjectId.value = null
            _progress.value = _progress.value.copy(
                isRunning = false,
                statusText = "Project frozen."
            )
            scope.launch {
                repository.freezeProject(projectId)
            }
        }
    }

    /**
     * Retries from the failed stage without resetting completed stages.
     */
    fun retry(context: Context, repository: ProjectRepository, projectId: String) {
        startOrResume(context, repository, projectId)
    }

    private fun updateProgress(
        projectId: String,
        stage: ProjectStage,
        stageName: String,
        percentage: Float,
        statusText: String
    ) {
        _progress.value = PipelineProgress(
            projectId = projectId,
            stage = stage,
            stageName = stageName,
            percentage = percentage,
            statusText = statusText,
            isRunning = true,
            error = null
        )
    }
}
