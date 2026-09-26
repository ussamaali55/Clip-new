package com.clipgenius.ai.ui.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CropRotate
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipgenius.ai.state.ClipCropPlan
import com.clipgenius.ai.state.ClipLayoutPlan
import com.clipgenius.ai.state.TranscriptSegment
import com.clipgenius.ai.state.CutStatus
import com.clipgenius.ai.state.LayoutReviewStatus
import com.clipgenius.ai.state.LayoutType
import com.clipgenius.ai.state.Project
import com.clipgenius.ai.state.TrackingMode
import com.clipgenius.ai.state.TrackingStatus
import com.clipgenius.ai.state.VerificationStatus
import com.clipgenius.ai.state.VerifiedClip
import com.clipgenius.ai.ui.components.AdjustCropDialog
import com.clipgenius.ai.ui.components.AdjustLayoutDialog
import com.clipgenius.ai.ui.components.AdjustTrimDialog
import com.clipgenius.ai.ui.components.CropOverlayPlayer
import com.clipgenius.ai.ui.components.InlineClipPreviewPlayer
import com.clipgenius.ai.verification.TimeUtils
import java.io.File
import java.util.Locale

/**
 * Phase 10 Tracking & Layout Configuration Stage UI.
 * Handles single active speaker reframing and multi-person splits (Stacked / Adaptive Grids).
 */
@Composable
fun TrackingStageContent(
    project: Project?,
    verifiedClips: List<VerifiedClip>,
    onCutClip: (clipId: String) -> Unit,
    onCutAllVerified: () -> Unit,
    onDeleteDraft: (clipId: String) -> Unit,
    onAdjustClipTimes: (clipId: String, startMs: Long, endMs: Long, hookStartMs: Long, hookEndMs: Long, timesChanged: Boolean) -> Unit,
    isCuttingQueue: Boolean,
    activeCuttingClipId: String?,
    activeClipProgress: Float,
    queueStatusText: String?,
    storageError: String?,
    onDismissStorageError: () -> Unit,
    onGoToClipPlan: () -> Unit,
    onAnalyzeFaceTrack: (clipId: String, force: Boolean) -> Unit = { _, _ -> },
    onRenderVertical: (clipId: String) -> Unit = {},
    onUpdateManualCrop: (clipId: String, offsetX: Float, margin: Float) -> Unit = { _, _, _ -> },
    onUpdateLayoutSelection: (clipId: String, layoutType: LayoutType) -> Unit = { _, _ -> },
    onUpdateSelectedPersons: (clipId: String, selectedIds: List<Int>) -> Unit = { _, _ -> },
    onApproveLayoutReview: (clipId: String) -> Unit = {},
    getCropPlan: suspend (VerifiedClip) -> ClipCropPlan = {
        ClipCropPlan(it.clipId, 1920, 1080, 608, 1080)
    },
    getLayoutPlan: suspend (VerifiedClip) -> ClipLayoutPlan = {
        ClipLayoutPlan(it.clipId, LayoutType.SINGLE, 1)
    },
    isTrackingProcessing: Boolean = false,
    activeTrackingClipId: String? = null,
    trackingProgress: Float = 0f,
    trackingStatusText: String? = null,
    onProceedToCaptions: () -> Unit = {}
) {
    var activeClipForAdjustTrim by remember { mutableStateOf<VerifiedClip?>(null) }
    var activeClipForAdjustCrop by remember { mutableStateOf<VerifiedClip?>(null) }
    var activeClipForAdjustLayout by remember { mutableStateOf<VerifiedClip?>(null) }
    var activeLayoutPlanForAdjustLayout by remember { mutableStateOf<ClipLayoutPlan?>(null) }

    val exportableClips = remember(verifiedClips) {
        verifiedClips.filter {
            (it.status == VerificationStatus.VERIFIED || it.isManuallyEdited) && it.isIncludedInExport
        }
    }

    val cutDraftsCount = remember(exportableClips) {
        exportableClips.count { it.cutStatus == CutStatus.DONE && !it.draftVideoPath.isNullOrBlank() }
    }

    val verticalDraftsCount = remember(exportableClips) {
        exportableClips.count {
            it.verticalDraftPath != null && File(it.verticalDraftPath).exists() && it.verticalQcPassed
        }
    }

    val isAllVerticalDone = exportableClips.isNotEmpty() && verticalDraftsCount == exportableClips.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. HERO HEADER CARD ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("tracking_hero_card")
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.CropRotate,
                    contentDescription = "Smart 9:16 Tracking",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Stage 6: Multi-Person Layouts & Grids",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Phase 10: Stacked Split-Screen & Adaptive Responsive Layouts",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Automatically formats multi-speaker videos into stacked split-screens or adaptive grids. Includes speaker dimming, real-time focus borders, and full Readability Guard checks.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // --- 2. STORAGE GUARD ALERT ---
        if (!storageError.isNullOrBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("tracking_storage_guard_alert")
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = storageError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    TextButton(onClick = onDismissStorageError) {
                        Text("Dismiss", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        // --- 3. GLOBAL STATUS & STAGE PROGRESS BAR ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(14.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Vertical Reframing Progress",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "$verticalDraftsCount of ${exportableClips.size} vertical 9:16 drafts rendered",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (isAllVerticalDone) {
                        Surface(
                            color = Color(0xFFE8F5E9),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("All Done", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            }
                        }
                    }
                }

                if (isTrackingProcessing) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = trackingStatusText ?: "Processing...",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "${(trackingProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { trackingProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // --- 4. CLIPS TRACKING LIST ---
        if (exportableClips.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No verified clips available.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Return to Stage 5 to verify clips before tracking.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onGoToClipPlan) {
                        Text("← Back to Clip Plan")
                    }
                }
            }
        } else {
            exportableClips.forEachIndexed { index, clip ->
                TrackingClipCard(
                    clip = clip,
                    index = index + 1,
                    onCutClip = { onCutClip(clip.clipId) },
                    onAdjustTrim = { activeClipForAdjustTrim = clip },
                    onAdjustCrop = { activeClipForAdjustCrop = clip },
                    onAdjustLayout = { plan ->
                        activeClipForAdjustLayout = clip
                        activeLayoutPlanForAdjustLayout = plan
                    },
                    onDeleteDraft = { onDeleteDraft(clip.clipId) },
                    onReanalyze = { onAnalyzeFaceTrack(clip.clipId, true) },
                    onRenderVertical = { onRenderVertical(clip.clipId) },
                    onApproveLayoutReview = { onApproveLayoutReview(clip.clipId) },
                    onSwitchToSingleSpeaker = { onUpdateLayoutSelection(clip.clipId, LayoutType.SINGLE_SPEAKER_FOLLOW) },
                    getCropPlan = getCropPlan,
                    getLayoutPlan = getLayoutPlan,
                    transcriptSegments = project?.transcript?.segments ?: emptyList(),
                    clipStartOffsetMs = clip.verifiedRanges.firstOrNull()?.startMs ?: 0L,
                    isProcessing = isTrackingProcessing && activeTrackingClipId == clip.clipId,
                    processingProgress = trackingProgress,
                    statusText = trackingStatusText
                )
            }
        }

        // --- 5. STAGE COMPLETION & FORWARD NAVIGATION ---
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (isAllVerticalDone) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("tracking_stage_completion_card")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isAllVerticalDone) "Stage 6 Complete • Ready for Captions" else "Vertical Framing in Progress",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isAllVerticalDone) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = if (isAllVerticalDone) {
                                "All $verticalDraftsCount clips have rendered 9:16 vertical drafts. Proceed to Stage 7 for word-by-word animated captions."
                            } else {
                                "$verticalDraftsCount of ${exportableClips.size} clips rendered. Render 9:16 for remaining clips to proceed."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isAllVerticalDone) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Button(
                        onClick = onProceedToCaptions,
                        enabled = isAllVerticalDone || verticalDraftsCount > 0,
                        modifier = Modifier.testTag("proceed_to_captions_button")
                    ) {
                        Text("Captions (Stage 7)")
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }

    // --- ADJUST TRIM DIALOG ---
    if (activeClipForAdjustTrim != null) {
        AdjustTrimDialog(
            clip = activeClipForAdjustTrim!!,
            sourceVideoPath = project?.sourceVideoPath,
            videoDurationMs = project?.sourceMedia?.durationMs ?: 0L,
            onDismiss = { activeClipForAdjustTrim = null },
            onSaveAndRecut = { sMs, eMs, hsMs, heMs, timesChanged ->
                onAdjustClipTimes(activeClipForAdjustTrim!!.clipId, sMs, eMs, hsMs, heMs, timesChanged)
                activeClipForAdjustTrim = null
            }
        )
    }

    // --- ADJUST CROP DIALOG ---
    if (activeClipForAdjustCrop != null) {
        AdjustCropDialog(
            clip = activeClipForAdjustCrop!!,
            initialOffsetX = activeClipForAdjustCrop!!.manualCropOffsetX,
            initialMargin = activeClipForAdjustCrop!!.manualFaceMargin,
            onDismiss = { activeClipForAdjustCrop = null },
            onSaveCropSettings = { offsetX, margin ->
                onUpdateManualCrop(activeClipForAdjustCrop!!.clipId, offsetX, margin)
                activeClipForAdjustCrop = null
            }
        )
    }

    // --- ADJUST LAYOUT & PERSON DIALOG ---
    if (activeClipForAdjustLayout != null && activeLayoutPlanForAdjustLayout != null) {
        AdjustLayoutDialog(
            clip = activeClipForAdjustLayout!!,
            layoutPlan = activeLayoutPlanForAdjustLayout!!,
            onDismiss = {
                activeClipForAdjustLayout = null
                activeLayoutPlanForAdjustLayout = null
            },
            onSaveLayout = { selectedType ->
                onUpdateLayoutSelection(activeClipForAdjustLayout!!.clipId, selectedType)
            },
            onSaveSelectedPersons = { selectedIds ->
                onUpdateSelectedPersons(activeClipForAdjustLayout!!.clipId, selectedIds)
            }
        )
    }
}

/**
 * Individual Clip Card on Tracking Stage.
 * Shows original draft with 9:16 crop overlay player, mode strip, layout indicators, and multi-person controls.
 */
@Composable
private fun TrackingClipCard(
    clip: VerifiedClip,
    index: Int,
    onCutClip: () -> Unit,
    onAdjustTrim: () -> Unit,
    onAdjustCrop: () -> Unit,
    onAdjustLayout: (ClipLayoutPlan) -> Unit,
    onDeleteDraft: () -> Unit,
    onReanalyze: () -> Unit,
    onRenderVertical: () -> Unit,
    onApproveLayoutReview: () -> Unit,
    onSwitchToSingleSpeaker: () -> Unit,
    getCropPlan: suspend (VerifiedClip) -> ClipCropPlan,
    getLayoutPlan: suspend (VerifiedClip) -> ClipLayoutPlan,
    transcriptSegments: List<TranscriptSegment>,
    clipStartOffsetMs: Long,
    isProcessing: Boolean,
    processingProgress: Float,
    statusText: String?
) {
    var cropPlan by remember { mutableStateOf<ClipCropPlan?>(null) }
    var layoutPlan by remember { mutableStateOf<ClipLayoutPlan?>(null) }
    var selectedPlayerTab by remember { mutableIntStateOf(0) } // 0: 9:16 Crop Overlay, 1: Rendered 9:16 Vertical

    val hasOriginalDraft = clip.cutStatus == CutStatus.DONE && !clip.draftVideoPath.isNullOrBlank()
    val hasVerticalDraft = clip.verticalDraftPath != null && File(clip.verticalDraftPath).exists() && clip.verticalQcPassed

    LaunchedEffect(clip.clipId, clip.draftVideoPath, clip.manualCropOffsetX, clip.manualFaceMargin, clip.layoutType, clip.selectedPersonIds) {
        if (hasOriginalDraft) {
            cropPlan = getCropPlan(clip)
            layoutPlan = getLayoutPlan(clip)
        }
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth().testTag("tracking_clip_card_${clip.clipId}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Index, Title, Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$index. ${clip.originalCandidate.title}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    
                    // Phase 10 Layout Label
                    val layoutLabel = when (clip.effectiveLayoutType) {
                        LayoutType.SINGLE -> "Single Crop"
                        LayoutType.SINGLE_SPEAKER_FOLLOW -> "Single Speaker Follow"
                        LayoutType.TWO_PERSON_STACKED -> "2-Person Stacked"
                        LayoutType.THREE_PERSON_GRID -> "3-Person Grid"
                        LayoutType.FOUR_PERSON_GRID -> "4-Person Grid"
                        LayoutType.AUTO -> "Auto"
                    }
                    Text(
                        text = "Layout: $layoutLabel (${clip.distinctPersonCount} distinct ${if (clip.distinctPersonCount == 1) "person" else "people"})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Status Badge
                when {
                    hasVerticalDraft -> {
                        Surface(color = Color(0xFFE8F5E9), shape = RoundedCornerShape(8.dp)) {
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("QC passed", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            }
                        }
                    }
                    isProcessing -> {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                            Text("Processing...", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                    hasOriginalDraft -> {
                        Surface(color = Color(0xFFE3F2FD), shape = RoundedCornerShape(8.dp)) {
                            Text("Tracked", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1976D2), modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                    else -> {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                            Text("Not Cut", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Phase 10: Readability Guard warning banner
            if (layoutPlan?.reviewStatus == LayoutReviewStatus.NEEDS_REVIEW) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .testTag("readability_guard_warning_${clip.clipId}")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.Warning, contentDescription = "Warning", tint = MaterialTheme.colorScheme.error)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Readability Guard Action Required",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = layoutPlan!!.reviewReason ?: "Faces are too small inside multi-person split screen. This renders unreadably tiny on phone displays.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = onApproveLayoutReview,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).height(36.dp).testTag("use_anyway_button")
                            ) {
                                Text("Use anyway", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                            
                            OutlinedButton(
                                onClick = onSwitchToSingleSpeaker,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1.2f).height(36.dp).testTag("switch_single_crop_button")
                            ) {
                                Text("Active speaker", fontSize = 11.sp, maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = { onAdjustLayout(layoutPlan!!) },
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f).height(36.dp).testTag("pick_who_show_button")
                            ) {
                                Text("Pick who", fontSize = 11.sp)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Body
            if (!hasOriginalDraft) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Draft video not yet sliced.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onCutClip, modifier = Modifier.testTag("cut_draft_first_button_${clip.clipId}")) {
                            Icon(imageVector = Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Cut Clip Draft")
                        }
                    }
                }
            } else {
                // Player Tab Selection (if vertical draft is also rendered)
                if (hasVerticalDraft) {
                    TabRow(
                        selectedTabIndex = selectedPlayerTab,
                        containerColor = Color.Transparent,
                        contentColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Tab(
                            selected = selectedPlayerTab == 0,
                            onClick = { selectedPlayerTab = 0 },
                            text = { Text("9:16 Crop Overlay") }
                        )
                        Tab(
                            selected = selectedPlayerTab == 1,
                            onClick = { selectedPlayerTab = 1 },
                            text = { Text("Rendered Vertical (9:16)") }
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Selected Player View
                if (selectedPlayerTab == 0 || !hasVerticalDraft) {
                    CropOverlayPlayer(
                        videoPath = clip.draftVideoPath!!,
                        cropPlan = cropPlan,
                        layoutPlan = layoutPlan,
                        transcriptSegments = transcriptSegments,
                        clipStartOffsetMs = clipStartOffsetMs,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    InlineClipPreviewPlayer(
                        videoPath = clip.verticalDraftPath!!,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Processing Progress Bar
                if (isProcessing) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(text = statusText ?: "Processing...", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Text(text = "${(processingProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { processingProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Actions Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left side secondary controls (Adjust Crop, Change Layout, Re-analyze)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = onAdjustCrop,
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .testTag("adjust_crop_button_${clip.clipId}")
                        ) {
                            Icon(imageVector = Icons.Default.Tune, contentDescription = "Adjust Crop", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }

                        IconButton(
                            onClick = { layoutPlan?.let { onAdjustLayout(it) } },
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .testTag("change_layout_button_${clip.clipId}")
                        ) {
                            Icon(imageVector = Icons.Default.Layers, contentDescription = "Change Layout", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }

                        IconButton(
                            onClick = onReanalyze,
                            enabled = !isProcessing,
                            modifier = Modifier
                                .size(40.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                                .testTag("reanalyze_button_${clip.clipId}")
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Re-analyze ML Kit", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }

                    // Right side main render button
                    Button(
                        onClick = onRenderVertical,
                        enabled = !isProcessing,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (hasVerticalDraft) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("render_vertical_button_${clip.clipId}")
                    ) {
                        Icon(imageVector = Icons.Default.CropRotate, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (hasVerticalDraft) "Re-render 9:16" else "Render 9:16")
                    }
                }
            }
        }
    }
}
