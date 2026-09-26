package com.clipgenius.ai.tracking

import android.content.Context
import android.util.Log
import com.clipgenius.ai.data.ProjectRepository
import com.clipgenius.ai.state.ClipCropPlan
import com.clipgenius.ai.state.ClipLayoutPlan
import com.clipgenius.ai.state.LayoutReviewStatus
import com.clipgenius.ai.state.LayoutType
import com.clipgenius.ai.state.TrackingStatus
import com.clipgenius.ai.state.TranscriptSegment
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 9 & 10 Tracking & 9:16 Vertical Reframing Manager.
 * Orchestrates ML Kit face track sampling, person clustering, multi-person layout planning,
 * Readability Guard review, and FFmpeg multi-panel vertical rendering.
 */
object TrackingManager {

    private const val TAG = "TrackingManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val cropPlanCache = ConcurrentHashMap<String, ClipCropPlan>()
    private val layoutPlanCache = ConcurrentHashMap<String, ClipLayoutPlan>()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _activeClipId = MutableStateFlow<String?>(null)
    val activeClipId: StateFlow<String?> = _activeClipId.asStateFlow()

    private val _operationProgress = MutableStateFlow(0f)
    val operationProgress: StateFlow<Float> = _operationProgress.asStateFlow()

    private val _operationStatusText = MutableStateFlow<String?>(null)
    val operationStatusText: StateFlow<String?> = _operationStatusText.asStateFlow()

    /**
     * Gets or calculates the CropPlan for a clip, using cached face track.
     */
    suspend fun getOrComputeCropPlan(
        context: Context,
        projectId: String,
        clip: VerifiedClip,
        transcriptSegments: List<TranscriptSegment> = emptyList(),
        forceReanalyze: Boolean = false
    ): ClipCropPlan = withContext(Dispatchers.IO) {
        val cacheKey = "${clip.clipId}_${clip.manualCropOffsetX}_${clip.manualFaceMargin}"
        if (!forceReanalyze && cropPlanCache.containsKey(cacheKey)) {
            return@withContext cropPlanCache[cacheKey]!!
        }

        val videoPath = clip.draftVideoPath ?: ""
        if (videoPath.isBlank() || !File(videoPath).exists()) {
            return@withContext CropPlanner.planCrop(
                clipId = clip.clipId,
                sourceWidth = 1920,
                sourceHeight = 1080,
                samples = emptyList(),
                manualCropOffsetX = clip.manualCropOffsetX,
                manualFaceMargin = clip.manualFaceMargin
            )
        }

        val trackResult = FaceTrackAnalyzer.analyzeClip(
            context = context,
            projectId = projectId,
            clipId = clip.clipId,
            videoPath = videoPath,
            forceReanalyze = forceReanalyze
        )

        val plan = CropPlanner.planCrop(
            clipId = clip.clipId,
            sourceWidth = trackResult.videoWidth,
            sourceHeight = trackResult.videoHeight,
            samples = trackResult.samples,
            transcriptSegments = transcriptSegments,
            clipStartOffsetMs = clip.verifiedRanges.firstOrNull()?.startMs ?: 0L,
            manualCropOffsetX = clip.manualCropOffsetX,
            manualFaceMargin = clip.manualFaceMargin
        )

        cropPlanCache[cacheKey] = plan
        plan
    }

    /**
     * Gets or calculates the multi-person ClipLayoutPlan for a clip.
     */
    suspend fun getOrComputeLayoutPlan(
        context: Context,
        projectId: String,
        clip: VerifiedClip,
        transcriptSegments: List<TranscriptSegment> = emptyList(),
        forceRecompute: Boolean = false
    ): ClipLayoutPlan = withContext(Dispatchers.IO) {
        val cacheKey = "${clip.clipId}_${clip.layoutType.name}_${clip.selectedPersonIds.joinToString(",")}"
        if (!forceRecompute && layoutPlanCache.containsKey(cacheKey)) {
            return@withContext layoutPlanCache[cacheKey]!!
        }

        val videoPath = clip.draftVideoPath ?: ""
        val trackResult = if (videoPath.isNotBlank() && File(videoPath).exists()) {
            FaceTrackAnalyzer.analyzeClip(
                context = context,
                projectId = projectId,
                clipId = clip.clipId,
                videoPath = videoPath,
                forceReanalyze = false
            )
        } else null

        val samples = trackResult?.samples ?: emptyList()
        val width = trackResult?.videoWidth ?: 1920
        val height = trackResult?.videoHeight ?: 1080

        val layoutPlan = LayoutDecider.decideLayout(
            context = context,
            projectId = projectId,
            clip = clip,
            samples = samples,
            sourceWidth = width,
            sourceHeight = height,
            transcriptSegments = transcriptSegments,
            clipStartOffsetMs = clip.verifiedRanges.firstOrNull()?.startMs ?: 0L,
            forceRecompute = forceRecompute
        )

        layoutPlanCache[cacheKey] = layoutPlan
        layoutPlan
    }

    /**
     * Runs ML Kit Face Track sampling and multi-person layout decision for a clip.
     */
    suspend fun analyzeClipTrack(
        context: Context,
        projectId: String,
        clipId: String,
        repository: ProjectRepository,
        forceReanalyze: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        val project = repository.getProjectById(projectId) ?: return@withContext false
        val clip = project.verifiedClips.find { it.clipId == clipId } ?: return@withContext false
        val videoPath = clip.draftVideoPath ?: return@withContext false

        _isProcessing.value = true
        _activeClipId.value = clipId
        _operationProgress.value = 0.05f
        _operationStatusText.value = "Analyzing faces with ML Kit..."

        updateClipInProject(repository, projectId, clipId) {
            it.copy(trackingStatus = TrackingStatus.ANALYZING, trackingProgress = 0.05f)
        }

        try {
            val trackResult = FaceTrackAnalyzer.analyzeClip(
                context = context,
                projectId = projectId,
                clipId = clipId,
                videoPath = videoPath,
                forceReanalyze = forceReanalyze,
                onProgress = { p ->
                    _operationProgress.value = p
                    _operationStatusText.value = "ML Kit Face Tracking: ${(p * 100).toInt()}%"
                }
            )

            val segments = project.transcript?.segments ?: emptyList()
            val plan = CropPlanner.planCrop(
                clipId = clipId,
                sourceWidth = trackResult.videoWidth,
                sourceHeight = trackResult.videoHeight,
                samples = trackResult.samples,
                transcriptSegments = segments,
                clipStartOffsetMs = clip.verifiedRanges.firstOrNull()?.startMs ?: 0L,
                manualCropOffsetX = clip.manualCropOffsetX,
                manualFaceMargin = clip.manualFaceMargin
            )

            cropPlanCache["${clipId}_${clip.manualCropOffsetX}_${clip.manualFaceMargin}"] = plan

            // Phase 10: Decide Layout (person counting, readability guard, panels)
            val layoutPlan = LayoutDecider.decideLayout(
                context = context,
                projectId = projectId,
                clip = clip,
                samples = trackResult.samples,
                sourceWidth = trackResult.videoWidth,
                sourceHeight = trackResult.videoHeight,
                transcriptSegments = segments,
                clipStartOffsetMs = clip.verifiedRanges.firstOrNull()?.startMs ?: 0L,
                forceRecompute = true
            )
            layoutPlanCache["${clipId}_${clip.layoutType.name}_${clip.selectedPersonIds.joinToString(",")}"] = layoutPlan

            updateClipInProject(repository, projectId, clipId) {
                it.copy(
                    trackingStatus = if (it.verticalDraftPath != null) TrackingStatus.DONE else TrackingStatus.TRACKED,
                    facetrackJsonPath = trackResult.jsonPath,
                    layoutJsonPath = layoutPlan.layoutJsonPath,
                    distinctPersonCount = layoutPlan.distinctPersonCount,
                    effectiveLayoutType = layoutPlan.layoutType,
                    layoutReviewStatus = layoutPlan.reviewStatus,
                    layoutReviewReason = layoutPlan.reviewReason,
                    trackingProgress = 1f,
                    dominantTrackingMode = plan.dominantMode,
                    trackingErrorMessage = null
                )
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Face track analysis failed", e)
            updateClipInProject(repository, projectId, clipId) {
                it.copy(
                    trackingStatus = TrackingStatus.ERROR,
                    trackingErrorMessage = e.message ?: "Face tracking failed."
                )
            }
            false
        } finally {
            _isProcessing.value = false
            _activeClipId.value = null
            _operationProgress.value = 0f
            _operationStatusText.value = null
        }
    }

    /**
     * Updates manual layout selection (Auto, 2-Person Stacked, 3/4-Person Grid, Single-Speaker Follow).
     */
    suspend fun updateLayoutSelection(
        context: Context,
        projectId: String,
        clipId: String,
        newLayoutType: LayoutType,
        repository: ProjectRepository
    ): ClipLayoutPlan = withContext(Dispatchers.IO) {
        val project = repository.getProjectById(projectId)
        val clip = project?.verifiedClips?.find { it.clipId == clipId }

        val updatedClip = (clip ?: VerifiedClip(clipId = clipId, originalCandidate = com.clipgenius.ai.state.ClipCandidate(clipId = clipId)))
            .copy(layoutType = newLayoutType)

        updateClipInProject(repository, projectId, clipId) {
            it.copy(layoutType = newLayoutType)
        }

        val layoutPlan = getOrComputeLayoutPlan(
            context = context,
            projectId = projectId,
            clip = updatedClip,
            transcriptSegments = project?.transcript?.segments ?: emptyList(),
            forceRecompute = true
        )

        updateClipInProject(repository, projectId, clipId) {
            it.copy(
                effectiveLayoutType = layoutPlan.layoutType,
                layoutReviewStatus = layoutPlan.reviewStatus,
                layoutReviewReason = layoutPlan.reviewReason
            )
        }

        layoutPlan
    }

    /**
     * User clicks "Use anyway" on Readability Guard review warning.
     */
    suspend fun approveLayoutReview(
        projectId: String,
        clipId: String,
        repository: ProjectRepository
    ) = withContext(Dispatchers.IO) {
        updateClipInProject(repository, projectId, clipId) {
            it.copy(
                layoutReviewStatus = LayoutReviewStatus.APPROVED_BY_USER,
                layoutReviewReason = null
            )
        }
    }

    /**
     * Updates included person IDs from the "Pick who to show" dialog and re-layouts.
     */
    suspend fun updateSelectedPersons(
        context: Context,
        projectId: String,
        clipId: String,
        selectedIds: List<Int>,
        repository: ProjectRepository
    ): ClipLayoutPlan = withContext(Dispatchers.IO) {
        val project = repository.getProjectById(projectId)
        val clip = project?.verifiedClips?.find { it.clipId == clipId }

        val updatedClip = (clip ?: VerifiedClip(clipId = clipId, originalCandidate = com.clipgenius.ai.state.ClipCandidate(clipId = clipId)))
            .copy(selectedPersonIds = selectedIds)

        updateClipInProject(repository, projectId, clipId) {
            it.copy(selectedPersonIds = selectedIds)
        }

        val layoutPlan = getOrComputeLayoutPlan(
            context = context,
            projectId = projectId,
            clip = updatedClip,
            transcriptSegments = project?.transcript?.segments ?: emptyList(),
            forceRecompute = true
        )

        updateClipInProject(repository, projectId, clipId) {
            it.copy(
                effectiveLayoutType = layoutPlan.layoutType,
                distinctPersonCount = layoutPlan.distinctPersonCount,
                layoutReviewStatus = layoutPlan.reviewStatus,
                layoutReviewReason = layoutPlan.reviewReason
            )
        }

        layoutPlan
    }

    /**
     * Updates manual crop position and face margin without re-running ML Kit face detection.
     */
    suspend fun updateManualCrop(
        context: Context,
        projectId: String,
        clipId: String,
        offsetX: Float,
        margin: Float,
        repository: ProjectRepository
    ): ClipCropPlan = withContext(Dispatchers.IO) {
        val project = repository.getProjectById(projectId)
        val clip = project?.verifiedClips?.find { it.clipId == clipId }

        updateClipInProject(repository, projectId, clipId) {
            it.copy(manualCropOffsetX = offsetX, manualFaceMargin = margin)
        }

        val updatedClip = clip?.copy(manualCropOffsetX = offsetX, manualFaceMargin = margin)
            ?: VerifiedClip(clipId = clipId, originalCandidate = com.clipgenius.ai.state.ClipCandidate(clipId = clipId))

        getOrComputeCropPlan(
            context = context,
            projectId = projectId,
            clip = updatedClip,
            transcriptSegments = project?.transcript?.segments ?: emptyList(),
            forceReanalyze = false
        )
    }

    /**
     * Renders a 9:16 vertical crop draft using FFmpeg (single or multi-person).
     */
    suspend fun renderVerticalDraft(
        context: Context,
        projectId: String,
        clipId: String,
        repository: ProjectRepository
    ): Boolean = withContext(Dispatchers.IO) {
        val project = repository.getProjectById(projectId) ?: return@withContext false
        val clip = project.verifiedClips.find { it.clipId == clipId } ?: return@withContext false
        val videoPath = clip.draftVideoPath ?: return@withContext false

        _isProcessing.value = true
        _activeClipId.value = clipId
        _operationProgress.value = 0.05f
        _operationStatusText.value = "Rendering 9:16 vertical video..."

        updateClipInProject(repository, projectId, clipId) {
            it.copy(trackingStatus = TrackingStatus.RENDERING, trackingProgress = 0.05f)
        }

        try {
            val segments = project.transcript?.segments ?: emptyList()
            val plan = getOrComputeCropPlan(context, projectId, clip, segments)
            val layoutPlan = getOrComputeLayoutPlan(context, projectId, clip, segments)
            val expectedDurMs = clip.cutDurationMs ?: clip.verifiedRanges.sumOf { it.endMs - it.startMs }

            val renderResult = VerticalRenderer.renderVertical(
                context = context,
                projectId = projectId,
                clipId = clipId,
                inputVideoPath = videoPath,
                cropPlan = plan,
                expectedDurationMs = expectedDurMs,
                layoutPlan = layoutPlan,
                onProgress = { p ->
                    _operationProgress.value = p
                    _operationStatusText.value = "Rendering 9:16: ${(p * 100).toInt()}%"
                }
            )

            val success = renderResult.success && renderResult.qcResult.passed

            updateClipInProject(repository, projectId, clipId) {
                if (success) {
                    it.copy(
                        trackingStatus = TrackingStatus.DONE,
                        verticalDraftPath = renderResult.outputPath,
                        verticalQcPassed = true,
                        verticalQcDetails = renderResult.qcResult.details,
                        trackingProgress = 1f,
                        trackingErrorMessage = null
                    )
                } else {
                    it.copy(
                        trackingStatus = TrackingStatus.ERROR,
                        verticalQcPassed = false,
                        trackingErrorMessage = renderResult.errorMessage ?: "Vertical render failed QC"
                    )
                }
            }

            // Check if all included clips have vertical draft -> mark Tracking stage completed!
            checkAndUpdateTrackingStageCompletion(projectId, repository)

            success
        } catch (e: Exception) {
            Log.e(TAG, "Vertical render failed", e)
            updateClipInProject(repository, projectId, clipId) {
                it.copy(
                    trackingStatus = TrackingStatus.ERROR,
                    trackingErrorMessage = e.message ?: "Vertical render failed"
                )
            }
            false
        } finally {
            _isProcessing.value = false
            _activeClipId.value = null
            _operationProgress.value = 0f
            _operationStatusText.value = null
        }
    }

    /**
     * Checks if all verified & included clips have a 9:16 vertical draft rendered.
     * When met, advances Tracking stage to completed and enables Captions stage!
     */
    private suspend fun checkAndUpdateTrackingStageCompletion(
        projectId: String,
        repository: ProjectRepository
    ) {
        val project = repository.getProjectById(projectId) ?: return
        val eligible = project.verifiedClips.filter {
            (it.status == com.clipgenius.ai.state.VerificationStatus.VERIFIED || it.isManuallyEdited) && it.isIncludedInExport
        }

        val allRendered = eligible.isNotEmpty() && eligible.all {
            it.verticalDraftPath != null && File(it.verticalDraftPath).exists()
        }

        if (allRendered) {
            val updatedStages = project.stages.toMutableMap().apply {
                put("Tracking", "completed")
                if (get("Captions") == "not_started" || get("Captions") == null) {
                    put("Captions", "in_progress")
                }
            }
            repository.saveProject(project.copy(stages = updatedStages))
        }
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
