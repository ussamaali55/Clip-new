package com.clipgenius.ai.ui.project

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.clipgenius.ai.state.CutStatus
import com.clipgenius.ai.state.Project
import com.clipgenius.ai.state.VerificationStatus
import com.clipgenius.ai.state.VerifiedClip
import com.clipgenius.ai.ui.components.AdjustTrimDialog
import com.clipgenius.ai.ui.components.InlineClipPreviewPlayer
import com.clipgenius.ai.verification.TimeUtils
import kotlinx.coroutines.delay
import java.io.File

/**
 * Phase 7 UI: Clip Plan & Deterministic Timestamp Verification Stage.
 * Ensures every AI-suggested timestamp is grounded in the real transcript.
 */
@Composable
fun ClipPlanStageContent(
    project: Project?,
    verifiedClips: List<VerifiedClip>,
    isVerifying: Boolean,
    verificationSummary: String?,
    onReVerify: () -> Unit,
    onUseFoundTime: (clipId: String) -> Unit,
    onManualEdit: (clipId: String, startMs: Long, endMs: Long, hookStartMs: Long, hookEndMs: Long) -> Unit,
    onDiscardClip: (clipId: String) -> Unit,
    onToggleExport: (clipId: String, isIncluded: Boolean) -> Unit,
    onGoToAiAnalysis: () -> Unit,
    onProceedToTracking: () -> Unit,
    onCutClip: (clipId: String) -> Unit = {},
    onCutAllVerified: () -> Unit = {},
    onDeleteDraft: (clipId: String) -> Unit = {},
    onAdjustClipTimes: (clipId: String, startMs: Long, endMs: Long, hookStartMs: Long, hookEndMs: Long, timesChanged: Boolean) -> Unit = { _, _, _, _, _, _ -> },
    isCuttingQueue: Boolean = false,
    activeCuttingClipId: String? = null,
    activeClipProgress: Float = 0f,
    queueStatusText: String? = null,
    storageError: String? = null,
    onDismissStorageError: () -> Unit = {}
) {
    var selectedFilterIndex by remember { mutableIntStateOf(0) } // 0: All, 1: Verified, 2: Needs Review, 3: Invalid
    var activeClipForManualEdit by remember { mutableStateOf<VerifiedClip?>(null) }
    var activeClipForAdjust by remember { mutableStateOf<VerifiedClip?>(null) }
    var previewClipForPlayback by remember { mutableStateOf<VerifiedClip?>(null) }

    val verifiedCount = remember(verifiedClips) {
        verifiedClips.count { it.status == VerificationStatus.VERIFIED }
    }
    val reviewCount = remember(verifiedClips) {
        verifiedClips.count { it.status == VerificationStatus.NEEDS_REVIEW }
    }
    val invalidCount = remember(verifiedClips) {
        verifiedClips.count { it.status == VerificationStatus.INVALID }
    }

    val filteredClips = remember(verifiedClips, selectedFilterIndex) {
        when (selectedFilterIndex) {
            1 -> verifiedClips.filter { it.status == VerificationStatus.VERIFIED }
            2 -> verifiedClips.filter { it.status == VerificationStatus.NEEDS_REVIEW }
            3 -> verifiedClips.filter { it.status == VerificationStatus.INVALID }
            else -> verifiedClips
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. HEADER CARD WITH VERIFICATION STATUS & RE-VERIFY ACTION ---
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("clip_plan_header_card")
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Stage 5: Clip Plan",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Deterministic Ground-Truth Verification",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedButton(
                        onClick = onReVerify,
                        enabled = !isVerifying,
                        modifier = Modifier.testTag("reverify_button")
                    ) {
                        if (isVerifying) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Verifying...")
                        } else {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Re-verify")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Summary Chips Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusBadge(
                        label = "$verifiedCount Verified",
                        color = Color(0xFF2E7D32),
                        bgColor = Color(0xFFE8F5E9),
                        icon = Icons.Default.CheckCircle
                    )
                    StatusBadge(
                        label = "$reviewCount Needs Review",
                        color = Color(0xFFE65100),
                        bgColor = Color(0xFFFFF3E0),
                        icon = Icons.Default.Warning
                    )
                    StatusBadge(
                        label = "$invalidCount Invalid",
                        color = Color(0xFFC62828),
                        bgColor = Color(0xFFFFEBEE),
                        icon = Icons.Default.Cancel
                    )
                }

                if (verificationSummary != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Engine Status: $verificationSummary",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // --- 2. EMPTY STATE CHECK ---
        if (verifiedClips.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No Clips Discovered Yet",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "AI virality discovery has not run for this project. Return to Stage 4 (AI Analysis) to discover clips first.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onGoToAiAnalysis,
                        modifier = Modifier.testTag("go_to_ai_analysis_button")
                    ) {
                        Text("← Go to AI Analysis (Stage 4)")
                    }
                }
            }
        } else {
            // --- STORAGE GUARD ALERT BANNER ---
            if (!storageError.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("storage_guard_alert")
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

            // --- PHASE 8 CUTTING QUEUE HEADER CARD ---
            if (verifiedCount > 0) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().testTag("cutting_queue_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Video Cutting Engine (FFmpeg)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            val doneCount = verifiedClips.count { it.cutStatus == CutStatus.DONE }
                            Text(
                                text = if (isCuttingQueue) {
                                    queueStatusText ?: "Processing cuts..."
                                } else {
                                    "$doneCount of $verifiedCount clips draft-cut"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Button(
                            onClick = onCutAllVerified,
                            enabled = !isCuttingQueue && verifiedCount > 0,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("cut_all_verified_button")
                        ) {
                            if (isCuttingQueue) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Cutting...")
                            } else {
                                Icon(imageVector = Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Cut All Verified")
                            }
                        }
                    }
                }
            }

            // --- 3. FILTER CHIPS ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedFilterIndex == 0,
                    onClick = { selectedFilterIndex = 0 },
                    label = { Text("All (${verifiedClips.size})") },
                    colors = FilterChipDefaults.filterChipColors()
                )
                FilterChip(
                    selected = selectedFilterIndex == 1,
                    onClick = { selectedFilterIndex = 1 },
                    label = { Text("Verified ($verifiedCount)") },
                    colors = FilterChipDefaults.filterChipColors()
                )
                FilterChip(
                    selected = selectedFilterIndex == 2,
                    onClick = { selectedFilterIndex = 2 },
                    label = { Text("Review ($reviewCount)") },
                    colors = FilterChipDefaults.filterChipColors()
                )
                FilterChip(
                    selected = selectedFilterIndex == 3,
                    onClick = { selectedFilterIndex = 3 },
                    label = { Text("Invalid ($invalidCount)") },
                    colors = FilterChipDefaults.filterChipColors()
                )
            }

            // --- 4. CLIPS LIST ---
            filteredClips.forEachIndexed { index, clip ->
                VerifiedClipItemCard(
                    clip = clip,
                    index = index + 1,
                    onUseFoundTime = { onUseFoundTime(clip.clipId) },
                    onEditManually = { activeClipForManualEdit = clip },
                    onDiscard = { onDiscardClip(clip.clipId) },
                    onToggleExport = { isIncluded -> onToggleExport(clip.clipId, isIncluded) },
                    onPreviewClick = { previewClipForPlayback = clip },
                    onCutClick = { onCutClip(clip.clipId) },
                    onAdjustClick = { activeClipForAdjust = clip },
                    onDeleteDraftClick = { onDeleteDraft(clip.clipId) },
                    activeCuttingClipId = activeCuttingClipId,
                    activeClipProgress = activeClipProgress
                )
            }

            // --- 5. BOTTOM NAVIGATION & READINESS ---
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (verifiedCount > 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("clip_plan_readiness_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (verifiedCount > 0) "Phase 8 Cutting Active • Ready for Tracking" else "Verification Pending",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (verifiedCount > 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (verifiedCount > 0) {
                                    "$verifiedCount clips verified. Cut individual clips or cut all into drafts, then proceed to smart subject tracking (Stage 6)."
                                } else {
                                    "No clips are currently verified. Correct review/invalid clips above to proceed."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (verifiedCount > 0) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Button(
                            onClick = onProceedToTracking,
                            enabled = verifiedCount > 0,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.testTag("proceed_to_tracking_button")
                        ) {
                            Text("Tracking (Stage 6)")
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    // --- MANUAL EDIT DIALOG ---
    if (activeClipForManualEdit != null) {
        ManualTimeEditDialog(
            clip = activeClipForManualEdit!!,
            videoPath = project?.sourceVideoPath,
            videoDurationMs = project?.sourceMedia?.durationMs ?: 0L,
            onDismiss = { activeClipForManualEdit = null },
            onSave = { sMs, eMs, hsMs, heMs ->
                onManualEdit(activeClipForManualEdit!!.clipId, sMs, eMs, hsMs, heMs)
                activeClipForManualEdit = null
            }
        )
    }

    // --- ADJUST TRIM DIALOG ---
    if (activeClipForAdjust != null) {
        AdjustTrimDialog(
            clip = activeClipForAdjust!!,
            sourceVideoPath = project?.sourceVideoPath,
            videoDurationMs = project?.sourceMedia?.durationMs ?: 0L,
            onDismiss = { activeClipForAdjust = null },
            onSaveAndRecut = { sMs, eMs, hsMs, heMs, timesChanged ->
                onAdjustClipTimes(activeClipForAdjust!!.clipId, sMs, eMs, hsMs, heMs, timesChanged)
                activeClipForAdjust = null
            }
        )
    }

    // --- MINI PREVIEW DIALOG ---
    if (previewClipForPlayback != null) {
        ClipPreviewDialog(
            clip = previewClipForPlayback!!,
            videoPath = project?.sourceVideoPath,
            onDismiss = { previewClipForPlayback = null }
        )
    }
}

/**
 * Single clip card reflecting verified, needs-review, or invalid status.
 */
@Composable
private fun VerifiedClipItemCard(
    clip: VerifiedClip,
    index: Int,
    onUseFoundTime: () -> Unit,
    onEditManually: () -> Unit,
    onDiscard: () -> Unit,
    onToggleExport: (Boolean) -> Unit,
    onPreviewClick: () -> Unit,
    onCutClick: () -> Unit = {},
    onAdjustClick: () -> Unit = {},
    onDeleteDraftClick: () -> Unit = {},
    activeCuttingClipId: String? = null,
    activeClipProgress: Float = 0f
) {
    val cand = clip.originalCandidate
    val statusColor = when (clip.status) {
        VerificationStatus.VERIFIED -> Color(0xFF2E7D32)
        VerificationStatus.NEEDS_REVIEW -> Color(0xFFE65100)
        VerificationStatus.INVALID -> Color(0xFFC62828)
    }
    val statusBg = when (clip.status) {
        VerificationStatus.VERIFIED -> Color(0xFFE8F5E9)
        VerificationStatus.NEEDS_REVIEW -> Color(0xFFFFF3E0)
        VerificationStatus.INVALID -> Color(0xFFFFEBEE)
    }

    val primaryRange = clip.verifiedRanges.firstOrNull() ?: cand.sourceRanges.firstOrNull()
    val startStr = TimeUtils.formatMs(primaryRange?.startMs ?: 0L)
    val endStr = TimeUtils.formatMs(primaryRange?.endMs ?: 0L)
    val durationSec = if (primaryRange != null) (primaryRange.endMs - primaryRange.startMs) / 1000 else 0

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("verified_clip_card_${clip.clipId}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Status badge, Score badge, and Export Checkbox
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(
                        label = when (clip.status) {
                            VerificationStatus.VERIFIED -> "Verified"
                            VerificationStatus.NEEDS_REVIEW -> "Needs Review"
                            VerificationStatus.INVALID -> "Invalid"
                        },
                        color = statusColor,
                        bgColor = statusBg,
                        icon = when (clip.status) {
                            VerificationStatus.VERIFIED -> Icons.Default.Check
                            VerificationStatus.NEEDS_REVIEW -> Icons.Default.Warning
                            VerificationStatus.INVALID -> Icons.Default.Cancel
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = if (cand.score >= 75) Color(0xFF2E7D32).copy(alpha = 0.15f) else Color(0xFFF57F17).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "${cand.score.toInt()} pts",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (cand.score >= 75) Color(0xFF2E7D32) else Color(0xFFF57F17),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                if (clip.status != VerificationStatus.INVALID) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Export",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Checkbox(
                            checked = clip.isIncludedInExport,
                            onCheckedChange = onToggleExport,
                            colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.testTag("export_checkbox_${clip.clipId}")
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Clip Title
            Text(
                text = "$index. ${cand.title}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Timings info
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Clip: $startStr - $endStr (${durationSec}s)",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "Hook: ${TimeUtils.formatMs(clip.verifiedHookStartMs)} - ${TimeUtils.formatMs(clip.verifiedHookEndMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Hook Sentence quote
            if (cand.hookSentence.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "\"${cand.hookSentence}\"",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // --- STATUS SPECIFIC SECTIONS ---

            when (clip.status) {
                VerificationStatus.NEEDS_REVIEW -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = Color(0xFFFFF3E0),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFE65100),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Timing Discrepancy > 10s Detected",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE65100)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            val claimedRange = cand.sourceRanges.firstOrNull()
                            val claimedStr = "${TimeUtils.formatMs(claimedRange?.startMs)} - ${TimeUtils.formatMs(claimedRange?.endMs)}"
                            val foundStr = "${TimeUtils.formatMs(clip.actualFoundStartMs)} - ${TimeUtils.formatMs(clip.actualFoundEndMs)}"

                            Text(
                                text = "AI Claimed: $claimedStr\nActually Found in Audio: $foundStr",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF4E342E)
                            )
                            if (clip.matchConfidence > 0f) {
                                Text(
                                    text = "Ground-truth text match: ${(clip.matchConfidence * 100).toInt()}%",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF6D4C41)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onUseFoundTime,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("use_found_time_button_${clip.clipId}")
                                ) {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Use Found Time")
                                }

                                OutlinedButton(
                                    onClick = onEditManually,
                                    modifier = Modifier.testTag("edit_manually_button_${clip.clipId}")
                                ) {
                                    Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Edit")
                                }

                                IconButton(
                                    onClick = onDiscard,
                                    modifier = Modifier.testTag("discard_button_${clip.clipId}")
                                ) {
                                    Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "Discard", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }

                VerificationStatus.INVALID -> {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        color = Color(0xFFFFEBEE),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Cancel,
                                    contentDescription = null,
                                    tint = Color(0xFFC62828),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Rejected by Verification Engine",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFC62828)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            clip.issues.forEach { issue ->
                                Text(
                                    text = "• $issue",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFB71C1C)
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = onEditManually,
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("salvage_button_${clip.clipId}")
                                ) {
                                    Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Salvage / Edit Times")
                                }

                                TextButton(
                                    onClick = onDiscard,
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.testTag("discard_invalid_button_${clip.clipId}")
                                ) {
                                    Text("Discard")
                                }
                            }
                        }
                    }
                }

                VerificationStatus.VERIFIED -> {
                    // Issues notes (e.g. hook clamped or manual edit)
                    if (clip.issues.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        clip.issues.forEach { issue ->
                            Text(
                                text = "ℹ $issue",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // --- PHASE 8 CUTTING ENGINE STATUS & PREVIEW ---
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.height(10.dp))

                    val isThisClipCutting = clip.cutStatus == CutStatus.CUTTING || activeCuttingClipId == clip.clipId
                    val effectiveProgress = if (activeCuttingClipId == clip.clipId && activeClipProgress > 0f) activeClipProgress else clip.cutProgress

                    when {
                        // 1. DONE: Preview player, QC Passed badge, Adjust, Delete draft
                        clip.cutStatus == CutStatus.DONE && !clip.draftVideoPath.isNullOrBlank() -> {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // QC Passed Badge
                                    Surface(
                                        color = Color(0xFFE8F5E9),
                                        shape = RoundedCornerShape(8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.CheckCircle,
                                                contentDescription = "QC Passed",
                                                tint = Color(0xFF2E7D32),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "QC passed",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF2E7D32)
                                            )
                                        }
                                    }

                                    // Duration & File Size
                                    val sizeMB = clip.cutFileSizeBytes?.let { String.format(java.util.Locale.US, "%.1f MB", it / (1024.0 * 1024.0)) } ?: ""
                                    val durSec = clip.cutDurationMs?.let { "${it / 1000}s" } ?: "${durationSec}s"
                                    Text(
                                        text = "$durSec • $sizeMB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Inline Video Preview Player
                                InlineClipPreviewPlayer(
                                    videoPath = clip.draftVideoPath,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                // Verified Start/End Times & Total Duration
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "Cut: $startStr - $endStr (${durationSec}s)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = onAdjustClick,
                                            modifier = Modifier.testTag("adjust_clip_button_${clip.clipId}")
                                        ) {
                                            Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Adjust")
                                        }

                                        TextButton(
                                            onClick = onDeleteDraftClick,
                                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                            modifier = Modifier.testTag("delete_draft_button_${clip.clipId}")
                                        ) {
                                            Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Delete Draft")
                                        }
                                    }
                                }
                            }
                        }

                        // 2. CUTTING: Progress bar and percentage
                        isThisClipCutting -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Cutting Clip Draft...",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Text(
                                        text = "${(effectiveProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { effectiveProgress.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // 3. ERROR: Cut failed, retry button
                        clip.cutStatus == CutStatus.ERROR -> {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.Cancel,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Cut Failed",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    clip.cutErrorMessage?.let {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = onCutClick,
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                            modifier = Modifier.testTag("retry_cut_button_${clip.clipId}")
                                        ) {
                                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Retry Cut")
                                        }
                                        OutlinedButton(onClick = onAdjustClick) {
                                            Text("Adjust")
                                        }
                                    }
                                }
                            }
                        }

                        // 4. NOT_CUT: Ready to cut
                        else -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Ready to cut ($durationSec s)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = onAdjustClick,
                                        modifier = Modifier.testTag("edit_times_button_${clip.clipId}")
                                    ) {
                                        Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Adjust")
                                    }

                                    Button(
                                        onClick = onCutClick,
                                        modifier = Modifier.testTag("cut_clip_button_${clip.clipId}")
                                    ) {
                                        Icon(imageVector = Icons.Default.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Cut Clip")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Reusable Status Chip Badge.
 */
@Composable
private fun StatusBadge(
    label: String,
    color: Color,
    bgColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

/**
 * Manual Override Dialog with MM:SS inputs and mini ExoPlayer preview player.
 */
@Composable
private fun ManualTimeEditDialog(
    clip: VerifiedClip,
    videoPath: String?,
    videoDurationMs: Long,
    onDismiss: () -> Unit,
    onSave: (startMs: Long, endMs: Long, hookStartMs: Long, hookEndMs: Long) -> Unit
) {
    val initialRange = clip.verifiedRanges.firstOrNull() ?: clip.originalCandidate.sourceRanges.firstOrNull()
    var startInput by remember { mutableStateOf(TimeUtils.formatMs(initialRange?.startMs ?: 0L)) }
    var endInput by remember { mutableStateOf(TimeUtils.formatMs(initialRange?.endMs ?: 60000L)) }
    var hookStartInput by remember { mutableStateOf(TimeUtils.formatMs(clip.verifiedHookStartMs)) }
    var hookEndInput by remember { mutableStateOf(TimeUtils.formatMs(clip.verifiedHookEndMs)) }
    var validationError by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosMs by remember { mutableLongStateOf(0L) }

    // Initialize mini ExoPlayer if video exists
    DisposableEffect(videoPath) {
        if (!videoPath.isNullOrBlank() && File(videoPath).exists()) {
            val player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(File(videoPath))))
                prepare()
                playWhenReady = false
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }
                })
            }
            exoPlayer = player
        }

        onDispose {
            exoPlayer?.release()
            exoPlayer = null
        }
    }

    // Position poller for mini preview
    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            currentPosMs = exoPlayer?.currentPosition ?: 0L
            delay(200)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Manual Override: Edit Times",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = clip.originalCandidate.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Mini Preview Player
                if (exoPlayer != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(130.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = false
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Play/Pause Overlay
                        IconButton(
                            onClick = {
                                exoPlayer?.let {
                                    if (it.isPlaying) it.pause() else it.play()
                                }
                            },
                            modifier = Modifier
                                .size(44.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Pos: ${TimeUtils.formatMs(currentPosMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            TextButton(
                                onClick = {
                                    val sMs = TimeUtils.parseToMs(startInput) ?: 0L
                                    exoPlayer?.seekTo(sMs)
                                    currentPosMs = sMs
                                }
                            ) {
                                Text("Seek Start", fontSize = 11.sp)
                            }
                            TextButton(
                                onClick = {
                                    val hsMs = TimeUtils.parseToMs(hookStartInput) ?: 0L
                                    exoPlayer?.seekTo(hsMs)
                                    currentPosMs = hsMs
                                }
                            ) {
                                Text("Seek Hook", fontSize = 11.sp)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Input fields
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = startInput,
                        onValueChange = { startInput = it },
                        label = { Text("Start (MM:SS)") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_start_input")
                    )
                    OutlinedTextField(
                        value = endInput,
                        onValueChange = { endInput = it },
                        label = { Text("End (MM:SS)") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_end_input")
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = hookStartInput,
                        onValueChange = { hookStartInput = it },
                        label = { Text("Hook Start") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_hook_start_input")
                    )
                    OutlinedTextField(
                        value = hookEndInput,
                        onValueChange = { hookEndInput = it },
                        label = { Text("Hook End") },
                        singleLine = true,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("manual_hook_end_input")
                    )
                }

                if (validationError != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = validationError ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val sMs = TimeUtils.parseToMs(startInput)
                    val eMs = TimeUtils.parseToMs(endInput)
                    val hsMs = TimeUtils.parseToMs(hookStartInput) ?: sMs ?: 0L
                    val heMs = TimeUtils.parseToMs(hookEndInput) ?: (hsMs + 3000L)

                    if (sMs == null || eMs == null) {
                        validationError = "Please enter valid times in MM:SS or HH:MM:SS format."
                        return@Button
                    }
                    if (sMs >= eMs) {
                        validationError = "Start time must be before end time."
                        return@Button
                    }
                    if (videoDurationMs > 0 && eMs > videoDurationMs) {
                        validationError = "End time exceeds video duration (${TimeUtils.formatMs(videoDurationMs)})."
                        return@Button
                    }
                    if ((eMs - sMs) < 30000L) { // 30s minimum clip limit
                        validationError = "Clip duration must be at least 30 seconds."
                        return@Button
                    }

                    validationError = null
                    onSave(sMs, eMs, hsMs, heMs)
                },
                modifier = Modifier.testTag("save_manual_times_button")
            ) {
                Text("Save & Verify")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Preview Player Dialog for testing audio & video match at clip timestamps.
 */
@Composable
private fun ClipPreviewDialog(
    clip: VerifiedClip,
    videoPath: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var exoPlayer by remember { mutableStateOf<ExoPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    val range = clip.verifiedRanges.firstOrNull() ?: clip.originalCandidate.sourceRanges.firstOrNull()
    val clipStartMs = range?.startMs ?: 0L
    val clipEndMs = range?.endMs ?: (clipStartMs + 60000L)

    DisposableEffect(videoPath) {
        if (!videoPath.isNullOrBlank() && File(videoPath).exists()) {
            val player = ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(Uri.fromFile(File(videoPath))))
                prepare()
                seekTo(clipStartMs)
                playWhenReady = true
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        isPlaying = playing
                    }
                })
            }
            exoPlayer = player
        }

        onDispose {
            exoPlayer?.release()
            exoPlayer = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Preview: ${clip.originalCandidate.title}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Playing: ${TimeUtils.formatMs(clipStartMs)} - ${TimeUtils.formatMs(clipEndMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    if (exoPlayer != null) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = exoPlayer
                                    useController = true
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Text("Video file not found", color = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "\"${clip.originalCandidate.transcriptText}\"",
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}
