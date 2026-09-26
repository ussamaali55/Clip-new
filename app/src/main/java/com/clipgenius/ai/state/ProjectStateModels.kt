package com.clipgenius.ai.state

import java.util.UUID

/**
 * Phase 1 Project Data Models
 * Defines all core entities used throughout the Clip Genius AI editing lifecycle.
 */

data class ActivityLogEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val stage: String,
    val operation: String,
    val status: String, // "SUCCESS", "FAILED", "RETRIED", "INFO"
    val details: String
)

enum class ProjectStage(val displayName: String, val badgeText: String, val defaultStageIndex: Int) {
    IMPORTED("Imported", "Video imported", 0),
    AUDIO_EXTRACTED("Audio Extracted", "Audio ready", 1),
    TRANSCRIBING("Transcribing", "Transcribing...", 2),
    TRANSCRIBED("Transcribed", "Transcript ready", 2),
    DISCOVERING_CLIPS("Discovering Clips", "Discovering clips...", 3),
    CLIPS_READY("Clips Ready", "Clips ready", 4),
    EDITING("Editing", "Editing", 4),
    RENDERING("Rendering", "Rendering...", 7),
    DONE("Done", "Complete", 8)
}

enum class ProjectStatus {
    ACTIVE,
    FROZEN
}

data class Project(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val sourceVideoPath: String = "",
    val videoUri: String = sourceVideoPath,
    val currentStage: ProjectStage = ProjectStage.IMPORTED,
    val status: ProjectStatus = ProjectStatus.ACTIVE,
    val stageDataJson: String? = null,
    val sourceMedia: SourceMedia? = null,
    val audioManifest: AudioChunkManifest? = null,
    val transcript: ProjectTranscript? = null,
    val clipCandidates: List<ClipCandidate> = emptyList(),
    val verifiedClips: List<VerifiedClip> = emptyList(),
    val stages: Map<String, String> = defaultStageStatuses(),
    val errorLog: String? = null,
    val activityLogs: List<ActivityLogEntry> = emptyList()
)

enum class ChunkStatus {
    QUEUED,
    PROCESSING,
    DONE,
    FAILED
}

data class AudioChunk(
    val index: Int,
    val filePath: String,
    val startMs: Long,
    val endMs: Long,
    val overlapMs: Long = 0L,
    val status: ChunkStatus = ChunkStatus.QUEUED,
    val errorMessage: String? = null
)

data class AudioChunkManifest(
    val fullAudioPath: String = "",
    val totalDurationMs: Long = 0L,
    val chunks: List<AudioChunk> = emptyList(),
    val isExtractionComplete: Boolean = false
)

fun defaultStageStatuses(): Map<String, String> {
    return mapOf(
        "Import" to "in_progress",
        "Audio" to "not_started",
        "Transcript" to "not_started",
        "AI Analysis" to "not_started",
        "Clip Plan" to "not_started",
        "Tracking" to "not_started",
        "Captions" to "not_started",
        "Render" to "not_started",
        "Complete" to "not_started"
    )
}

data class SourceMedia(
    val path: String = "",
    val fileName: String = "",
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
    val frameRate: Float = 0f,
    val hasAudio: Boolean = false,
    val codec: String = "",
    val audioCodec: String = "",
    val fileSizeBytes: Long = 0L
)

data class TranscriptWord(
    val word: String,
    val startMs: Long,
    val endMs: Long,
    val speaker: Int? = null,
    val confidence: Float? = null
)

data class TranscriptSegment(
    val id: String = UUID.randomUUID().toString(),
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speakerLabel: String? = null,
    val words: List<TranscriptWord> = emptyList(),
    val isEdited: Boolean = false
)

data class ProjectTranscript(
    val segments: List<TranscriptSegment> = emptyList(),
    val isFallbackGemini: Boolean = false,
    val totalWords: Int = 0,
    val totalSpeakers: Int = 0,
    val srtPath: String = "",
    val jsonPath: String = "",
    val completedAt: Long = System.currentTimeMillis()
)

data class SourceRange(
    val startMs: Long,
    val endMs: Long,
    val rawStart: String? = null,
    val rawEnd: String? = null
)

data class ClipCandidate(
    val clipId: String = UUID.randomUUID().toString(),
    val sourceRanges: List<SourceRange> = emptyList(),
    val hookStartMs: Long = 0L,
    val hookEndMs: Long = 0L,
    val rawHookStart: String? = null,
    val rawHookEnd: String? = null,
    val transcriptText: String = "",
    val score: Float = 0f,
    val title: String = "",
    val hookSentence: String = "",
    val viralityReason: String = "",
    val caption: String = "",
    val hashtags: List<String> = emptyList(),
    val isIncludedInExport: Boolean = true
)

enum class VerificationStatus {
    VERIFIED,
    NEEDS_REVIEW,
    INVALID
}

enum class CutStatus {
    NOT_CUT,
    CUTTING,
    DONE,
    ERROR
}

enum class TrackingStatus {
    NOT_TRACKED,
    ANALYZING,
    TRACKED,
    RENDERING,
    DONE,
    ERROR
}

enum class TrackingMode {
    FACE,
    MOVEMENT,
    CENTER
}

enum class LayoutType {
    AUTO,
    SINGLE,
    TWO_PERSON_STACKED,
    THREE_PERSON_GRID,
    FOUR_PERSON_GRID,
    SINGLE_SPEAKER_FOLLOW
}

enum class LayoutReviewStatus {
    OK,
    NEEDS_REVIEW,
    APPROVED_BY_USER
}

data class PersonProfile(
    val personId: Int,
    val trackingId: Int? = null,
    val label: String = "Person $personId",
    val avgBoundingBox: TrackedFace,
    val totalVisibleMs: Long = 0L,
    val visibleRatio: Float = 0f,
    val assignedSpeakerLabel: String? = null,
    val isIncluded: Boolean = true
)

data class PanelCropConfig(
    val panelIndex: Int,
    val personId: Int,
    val panelNormX: Float,
    val panelNormY: Float,
    val panelNormWidth: Float,
    val panelNormHeight: Float,
    val cropPlan: ClipCropPlan,
    val faceHeightRatio: Float
)

data class LayoutSwitchPoint(
    val timestampMs: Long,
    val fromLayout: LayoutType,
    val toLayout: LayoutType,
    val activePersonCount: Int
)

data class ClipLayoutPlan(
    val clipId: String,
    val layoutType: LayoutType,
    val distinctPersonCount: Int,
    val persons: List<PersonProfile> = emptyList(),
    val panels: List<PanelCropConfig> = emptyList(),
    val switchPoints: List<LayoutSwitchPoint> = emptyList(),
    val reviewStatus: LayoutReviewStatus = LayoutReviewStatus.OK,
    val reviewReason: String? = null,
    val layoutJsonPath: String? = null
)

data class TrackedFace(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val trackingId: Int? = null,
    val confidence: Float = 1.0f
)

data class FaceSample(
    val timestampMs: Long,
    val faces: List<TrackedFace> = emptyList(),
    val movementScore: Float = 0f,
    val movementCenterX: Float = 0.5f,
    val mode: TrackingMode = TrackingMode.FACE
)

data class CropKeyframe(
    val timestampMs: Long,
    val cropX: Int,
    val cropY: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val mode: TrackingMode = TrackingMode.FACE,
    val faceBox: TrackedFace? = null
)

data class ClipCropPlan(
    val clipId: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val cropWidth: Int,
    val cropHeight: Int,
    val keyframes: List<CropKeyframe> = emptyList(),
    val manualCropOffsetX: Float = 0f,
    val manualFaceMargin: Float = 1.0f,
    val dominantMode: TrackingMode = TrackingMode.FACE
)

data class VerifiedClip(
    val clipId: String = UUID.randomUUID().toString(),
    val verifiedRanges: List<SourceRange> = emptyList(),
    val verifiedHookStartMs: Long = 0L,
    val verifiedHookEndMs: Long = 0L,
    val status: VerificationStatus = VerificationStatus.NEEDS_REVIEW,
    val issues: List<String> = emptyList(),
    val originalCandidate: ClipCandidate,
    val isManuallyEdited: Boolean = false,
    val actualFoundStartMs: Long? = null,
    val actualFoundEndMs: Long? = null,
    val matchConfidence: Float = 0f,
    val isIncludedInExport: Boolean = true,
    val cutStatus: CutStatus = CutStatus.NOT_CUT,
    val draftVideoPath: String? = null,
    val cutProgress: Float = 0f,
    val cutErrorMessage: String? = null,
    val qcPassed: Boolean = false,
    val qcDetails: String? = null,
    val cutDurationMs: Long? = null,
    val cutFileSizeBytes: Long? = null,
    val trackingStatus: TrackingStatus = TrackingStatus.NOT_TRACKED,
    val facetrackJsonPath: String? = null,
    val verticalDraftPath: String? = null,
    val trackingProgress: Float = 0f,
    val trackingErrorMessage: String? = null,
    val verticalQcPassed: Boolean = false,
    val verticalQcDetails: String? = null,
    val manualCropOffsetX: Float = 0f,
    val manualFaceMargin: Float = 1.0f,
    val dominantTrackingMode: TrackingMode = TrackingMode.FACE,
    val layoutType: LayoutType = LayoutType.AUTO,
    val effectiveLayoutType: LayoutType = LayoutType.SINGLE,
    val distinctPersonCount: Int = 1,
    val layoutReviewStatus: LayoutReviewStatus = LayoutReviewStatus.OK,
    val layoutReviewReason: String? = null,
    val layoutJsonPath: String? = null,
    val selectedPersonIds: List<Int> = emptyList(),
    val captionPresetId: String? = null,
    val burnCaptions: Boolean = true,
    val captionsStatus: String = "NOT_STARTED",
    val captionsJsonPath: String? = null,
    val selectedFilter: String = "None",
    val isMirrored: Boolean = false,
    val manualReframeOffsetX: Float = 0f,
    val manualReframeZoom: Float = 1.0f,
    val finalExportPath: String? = null,
    val finalExportStatus: RenderStatus = RenderStatus.PENDING,
    val finalExportProgress: Float = 0f,
    val finalExportErrorMessage: String? = null,
    val finalQcPassed: Boolean = false,
    val finalQcDetails: List<String> = emptyList(),
    val mediaStoreUri: String? = null,
    val metadataTxtPath: String? = null
)

data class CaptionPreset(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "Default Bold",
    val fontFamily: String = "Inter",
    val fontSizeSp: Float = 24f,
    val fontWeight: Int = 700,
    val textColor: String = "#FFFFFF",
    val strokeColor: String = "#000000",
    val strokeWidthDp: Float = 2f,
    val shadowColor: String = "#80000000",
    val shadowRadiusDp: Float = 4f,
    val backgroundColor: String = "#00000000",
    val textAlignment: String = "CENTER",
    val verticalPositionPercent: Float = 80f,
    val animationType: String = "word-highlight",
    val animationDurationMs: Int = 200,
    val wordEmphasisColor: String = "#FFFF00",
    val allCaps: Boolean = true,
    val isImported: Boolean = false
)

data class CaptionWordTiming(
    val word: String,
    val startMs: Long,
    val endMs: Long
)

data class CaptionCue(
    val id: String = java.util.UUID.randomUUID().toString(),
    val cueStartMs: Long,
    val cueEndMs: Long,
    val text: String,
    val lines: List<String> = emptyList(),
    val wordTimings: List<CaptionWordTiming> = emptyList(),
    val isEdited: Boolean = false
)

data class ExportSettings(
    val resolution: String = "1080x1920",
    val frameRate: Int = 30,
    val videoBitrate: Int = 8000000,
    val audioBitrate: Int = 192000
)

enum class RenderStatus {
    PENDING,
    PROCESSING,
    COMPLETE,
    ERROR
}

data class RenderJob(
    val clipId: String,
    val status: RenderStatus = RenderStatus.PENDING,
    val outputPath: String? = null
)

enum class StageInfo(
    val key: String,
    val displayName: String,
    val phaseNumber: Int,
    val description: String
) {
    IMPORT("Import", "Import", 2, "Video import and media verification"),
    AUDIO("Audio", "Audio", 3, "Audio track extraction & waveform generation"),
    TRANSCRIPT("Transcript", "Transcript", 4, "Speech-to-text transcript via Deepgram"),
    AI_ANALYSIS("AI Analysis", "AI Analysis", 5, "Gemini AI virality scoring & hook detection"),
    CLIP_PLAN("Clip Plan", "Clip Plan", 6, "Automated clip framing & duration strategy"),
    TRACKING("Tracking", "Tracking", 7, "Smart subject tracking & dynamic 9:16 crop"),
    CAPTIONS("Captions", "Captions", 8, "Word-by-word animated caption styling"),
    RENDER("Render", "Render", 9, "FFmpeg video export engine"),
    COMPLETE("Complete", "Complete", 10, "Final export management & sharing")
}
