package com.clipgenius.ai.ui.project

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipgenius.ai.render.EffectsEngine
import com.clipgenius.ai.state.Project
import com.clipgenius.ai.state.RenderStatus
import com.clipgenius.ai.state.VerificationStatus
import com.clipgenius.ai.state.VerifiedClip
import com.clipgenius.ai.ui.components.InlineClipPreviewPlayer
import java.io.File
import java.util.Locale

/**
 * Phase 12 Render & Final Export Studio Stage.
 * - Live Effects Panel: 6 looks, mirror video, manual reframe zoom (1.0x-1.5x) and position sliders
 * - "Export All" batch render and individual render with single-pass libx264/AAC encoding
 * - 7-point Broadcast Quality Checklist (QC passed badge or failure breakdown)
 * - Sidecar metadata (.txt) generator and one-tap "Copy caption + hashtags" button
 * - MediaStore integration: saved videos automatically populate Movies/ClipGenius gallery
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RenderStageContent(
    project: Project?,
    verifiedClips: List<VerifiedClip>,
    isExporting: Boolean,
    activeExportClipId: String?,
    exportProgress: Float,
    exportStatusText: String?,
    onExportAll: (resumeOnly: Boolean) -> Unit,
    onRenderClip: (String) -> Unit,
    onUpdateFilter: (clipId: String, filter: String) -> Unit,
    onToggleMirror: (clipId: String, mirrored: Boolean) -> Unit,
    onUpdateReframe: (clipId: String, offsetX: Float, zoom: Float) -> Unit,
    onOpenActivityLog: () -> Unit
) {
    val context = LocalContext.current

    val exportableClips = remember(verifiedClips) {
        verifiedClips.filter {
            (it.status == VerificationStatus.VERIFIED || it.isManuallyEdited) && it.isIncludedInExport
        }
    }

    val completedCount = remember(exportableClips) {
        exportableClips.count { it.finalExportStatus == RenderStatus.COMPLETE && it.finalQcPassed }
    }
    val allCompleted = exportableClips.isNotEmpty() && completedCount == exportableClips.size
    val canResume = completedCount > 0 && !allCompleted

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. HERO HEADER ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("render_hero_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.MovieFilter,
                        contentDescription = "Export Studio",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Stage 8: Effects Engine & Final Export",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Applies visual look filters, camera reframing, and encodes complete videos directly to your device's gallery.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // --- 2. GLOBAL ACTIONS & EXPORT ALL BAR ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth().testTag("export_all_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Export Studio Status",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "$completedCount of ${exportableClips.size} clips exported to gallery",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onOpenActivityLog,
                                modifier = Modifier.height(38.dp).testTag("activity_log_button")
                            ) {
                                Icon(imageVector = Icons.Default.History, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Log", fontSize = 12.sp)
                            }

                            if (canResume && !isExporting) {
                                OutlinedButton(
                                    onClick = { onExportAll(true) },
                                    modifier = Modifier.height(38.dp).testTag("resume_export_button")
                                ) {
                                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Resume (${exportableClips.size - completedCount})", fontSize = 12.sp)
                                }
                            }

                            Button(
                                onClick = { onExportAll(false) },
                                enabled = !isExporting && exportableClips.isNotEmpty(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (allCompleted) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.height(38.dp).testTag("export_all_button")
                            ) {
                                if (isExporting) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Exporting...", fontSize = 12.sp)
                                } else {
                                    Icon(imageVector = Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(if (allCompleted) "Re-export All" else "Export All", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    if (isExporting) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = exportStatusText ?: "Processing export queue...",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${(exportProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { exportProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // --- 3. CLIPS LIST WITH EFFECTS & QC ---
        if (exportableClips.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                    Text("No exportable clips available. Please verify clips in earlier stages.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else {
            itemsIndexed(exportableClips) { index, clip ->
                RenderClipCard(
                    clip = clip,
                    index = index + 1,
                    isProcessing = isExporting && activeExportClipId == clip.clipId,
                    onRender = { onRenderClip(clip.clipId) },
                    onUpdateFilter = { filter -> onUpdateFilter(clip.clipId, filter) },
                    onToggleMirror = { mirrored -> onToggleMirror(clip.clipId, mirrored) },
                    onUpdateReframe = { offsetX, zoom -> onUpdateReframe(clip.clipId, offsetX, zoom) }
                )
            }
        }
    }
}

/**
 * Individual Clip Card on Render Stage.
 * Shows Live Preview with instant visual effects, filter looks selector, mirror toggle,
 * manual reframe sliders, QC report, and TikTok metadata copy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RenderClipCard(
    clip: VerifiedClip,
    index: Int,
    isProcessing: Boolean,
    onRender: () -> Unit,
    onUpdateFilter: (String) -> Unit,
    onToggleMirror: (Boolean) -> Unit,
    onUpdateReframe: (Float, Float) -> Unit
) {
    val context = LocalContext.current
    var showEffectsPanel by remember { mutableStateOf(false) }
    var showQcDetails by remember { mutableStateOf(false) }

    val isDone = clip.finalExportStatus == RenderStatus.COMPLETE && clip.finalQcPassed
    val isError = clip.finalExportStatus == RenderStatus.ERROR

    // Video path to preview (use final export if ready, otherwise vertical draft or source draft)
    val videoPreviewPath = clip.finalExportPath ?: clip.verticalDraftPath ?: clip.draftVideoPath ?: ""

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth().testTag("render_clip_card_${clip.clipId}")
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Index, Title, Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$index. ${clip.originalCandidate.title}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val durSec = String.format(Locale.US, "%.1fs", (clip.cutDurationMs ?: 0L) / 1000f)
                    Text(
                        text = "Length: $durSec • Look: ${clip.selectedFilter}${if (clip.isMirrored) " • Mirrored" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                when {
                    isDone -> {
                        Surface(color = Color(0xFFE8F5E9), shape = RoundedCornerShape(8.dp)) {
                            Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("QC Passed", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                            }
                        }
                    }
                    isProcessing -> {
                        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp)) {
                            Text("Rendering...", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                    isError -> {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                            Text("QC Issue", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                    else -> {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                            Text("Ready to Render", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Live Preview with real-time effect matrix & look tint
            if (videoPreviewPath.isNotBlank() && File(videoPreviewPath).exists()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = if (clip.isMirrored) -clip.manualReframeZoom else clip.manualReframeZoom
                                scaleY = clip.manualReframeZoom
                                translationX = clip.manualReframeOffsetX * 150f
                            }
                    ) {
                        InlineClipPreviewPlayer(
                            videoPath = videoPreviewPath,
                            modifier = Modifier.fillMaxSize()
                        )
                        // Live Filter Tint Overlay
                        val tintColor = when (clip.selectedFilter) {
                            "Warm" -> Color(0xFFFFB300).copy(alpha = 0.15f)
                            "Cool" -> Color(0xFF00B0FF).copy(alpha = 0.15f)
                            "High Contrast" -> Color(0xFF000000).copy(alpha = 0.10f)
                            "Vintage" -> Color(0xFF8D6E63).copy(alpha = 0.18f)
                            "Black & White" -> Color(0xFF1E1E1E).copy(alpha = 0.35f)
                            else -> Color.Transparent
                        }
                        if (tintColor != Color.Transparent) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(tintColor)
                            )
                        }
                    }
                }
            } else {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(100.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Draft video not generated. Click Render below.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Effects Panel Toggle Bar & TikTok Metadata Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { showEffectsPanel = !showEffectsPanel },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(34.dp).testTag("effects_panel_toggle_${clip.clipId}")
                ) {
                    Icon(imageVector = Icons.Default.Tune, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (showEffectsPanel) "Hide Effects Panel" else "Tune Effects & Looks", fontSize = 11.sp)
                }

                // Copy TikTok Metadata Button
                OutlinedButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val hashtags = if (clip.originalCandidate.hashtags.isNotEmpty()) {
                            clip.originalCandidate.hashtags.joinToString(" ") { if (it.startsWith("#")) it else "#$it" }
                        } else {
                            "#shorts #viral #fyp"
                        }
                        val textToCopy = "${clip.originalCandidate.title}\n\n${clip.originalCandidate.caption.ifBlank { clip.originalCandidate.hookSentence }}\n\n$hashtags"
                        clipboard.setPrimaryClip(ClipData.newPlainText("TikTok Caption", textToCopy))
                        Toast.makeText(context, "Copied caption & hashtags for TikTok!", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(34.dp).testTag("copy_metadata_btn_${clip.clipId}")
                ) {
                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy caption + hashtags", fontSize = 11.sp)
                }
            }

            // --- EXPANDED EFFECTS PANEL ---
            if (showEffectsPanel) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().testTag("effects_panel_${clip.clipId}")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // 1. Color Filters Row with Visual Swatches
                        Text("Color Look Filter", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            EffectsEngine.FILTER_NAMES.forEach { filterName ->
                                val isSelected = clip.selectedFilter == filterName
                                val swatchBrush = when (filterName) {
                                    "Warm" -> Brush.verticalGradient(listOf(Color(0xFFFFB300), Color(0xFFE65100)))
                                    "Cool" -> Brush.verticalGradient(listOf(Color(0xFF00E5FF), Color(0xFF0D47A1)))
                                    "High Contrast" -> Brush.verticalGradient(listOf(Color(0xFFFFFFFF), Color(0xFF000000)))
                                    "Vintage" -> Brush.verticalGradient(listOf(Color(0xFFD7CCC8), Color(0xFF5D4037)))
                                    "Black & White" -> Brush.verticalGradient(listOf(Color(0xFFE0E0E0), Color(0xFF212121)))
                                    else -> Brush.verticalGradient(listOf(Color(0xFF9E9E9E), Color(0xFF616161)))
                                }
                                Surface(
                                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier
                                        .clickable { onUpdateFilter(filterName) }
                                        .padding(vertical = 2.dp)
                                        .border(
                                            width = if (isSelected) 2.dp else 0.5.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.5f),
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .testTag("filter_opt_${clip.clipId}_$filterName")
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        modifier = Modifier.padding(6.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(width = 44.dp, height = 26.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(swatchBrush),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = filterName,
                                            fontSize = 10.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(8.dp))

                        // 2. Mirror Video
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text("Mirror Video", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text("Horizontal flip (hflip)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = clip.isMirrored,
                                onCheckedChange = { onToggleMirror(it) },
                                modifier = Modifier.testTag("mirror_switch_${clip.clipId}")
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        HorizontalDivider(color = Color.LightGray.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(8.dp))

                        // 3. Manual Reframe Zoom & Offset
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("Zoom & Crop Reframe", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            TextButton(
                                onClick = { onUpdateReframe(0f, 1.0f) },
                                modifier = Modifier.testTag("reset_reframe_btn_${clip.clipId}")
                            ) {
                                Text("Reset to auto", fontSize = 11.sp)
                            }
                        }

                        // Zoom Slider (1.0x to 1.5x)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Zoom: ${String.format(Locale.US, "%.2fx", clip.manualReframeZoom)}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(75.dp))
                            Slider(
                                value = clip.manualReframeZoom,
                                onValueChange = { newZoom -> onUpdateReframe(clip.manualReframeOffsetX, newZoom) },
                                valueRange = 1.0f..1.5f,
                                modifier = Modifier.weight(1f).testTag("zoom_slider_${clip.clipId}")
                            )
                        }

                        // Offset Slider (-1.0 to 1.0)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Pan: ${if (clip.manualReframeOffsetX > 0) "+" else ""}${String.format(Locale.US, "%.2f", clip.manualReframeOffsetX)}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(75.dp))
                            Slider(
                                value = clip.manualReframeOffsetX,
                                onValueChange = { newOff -> onUpdateReframe(newOff, clip.manualReframeZoom) },
                                valueRange = -1.0f..1.0f,
                                modifier = Modifier.weight(1f).testTag("pan_slider_${clip.clipId}")
                            )
                        }
                    }
                }
            }

            // QC Failure / Success Card with Expandable Breakdown
            if (isDone) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = Color(0xFFF1F8E9),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("qc_passed_box_${clip.clipId}")
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showQcDetails = !showQcDetails },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF388E3C), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "All 7 Broadcast Standards Passed • In Gallery",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                            Icon(
                                imageVector = if (showQcDetails) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle QC Breakdown",
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        if (showQcDetails) {
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = Color(0xFFC8E6C9))
                            Spacer(modifier = Modifier.height(6.dp))
                            val defaultChecks = listOf(
                                "(a) Duration Accuracy: ±2s broadcast tolerance",
                                "(b) Portrait Aspect: exactly 9:16 portrait (1080x1920)",
                                "(c) Audio Stream & Sync: present and synchronized",
                                "(d) Clean Edge Fill: zero black bars/pillarboxing",
                                "(e) Face Framing Safety: ML Kit validated within safe margin",
                                "(f) Platform Subtitles: visible within safe areas",
                                "(g) Playability: valid decodable video stream verified"
                            )
                            val checksToShow = if (clip.finalQcDetails.isNotEmpty()) clip.finalQcDetails else defaultChecks
                            checksToShow.forEach { detail ->
                                Row(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(12.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(detail, fontSize = 10.sp, color = Color(0xFF1B5E20))
                                }
                            }
                        }
                    }
                }
            } else if (isError && !clip.finalExportErrorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().testTag("qc_error_box_${clip.clipId}")
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(imageVector = Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("QC Verification Failure", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = clip.finalExportErrorMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Bottom Actions (Render button)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onRender,
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isDone) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.testTag("render_clip_button_${clip.clipId}")
                ) {
                    Icon(imageVector = Icons.Default.Movie, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (isDone) "Re-render" else "Render Final Video")
                }
            }
        }
    }
}
