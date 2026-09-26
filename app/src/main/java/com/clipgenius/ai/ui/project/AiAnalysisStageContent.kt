package com.clipgenius.ai.ui.project

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipgenius.ai.state.ClipCandidate
import com.clipgenius.ai.state.Project
import com.clipgenius.ai.state.ProjectTranscript
import com.clipgenius.ai.state.SourceRange
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiAnalysisStageContent(
    project: Project?,
    transcript: ProjectTranscript?,
    clipCandidates: List<ClipCandidate>,
    isAnalyzing: Boolean,
    progressText: String,
    analysisError: String?,
    clipsStatusMessage: String?,
    hasGeminiKey: Boolean,
    onFindClips: (requestedCount: Int, minLengthSeconds: Int) -> Unit,
    onToggleExport: (clipId: String, isIncluded: Boolean) -> Unit,
    onDismissError: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onGoToTranscriptStage: () -> Unit,
    onProceedToClipPlan: () -> Unit
) {
    val context = LocalContext.current

    // Local configuration states
    var requestedClipsCount by remember { mutableIntStateOf(10) }
    var minClipLengthSeconds by remember { mutableIntStateOf(60) }
    var showConfigControls by remember { mutableStateOf(clipCandidates.isEmpty()) }

    // If no candidates exist, config controls must stay visible
    val isConfigVisible = showConfigControls || clipCandidates.isEmpty()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("ai_analysis_stage_content")
    ) {
        // Missing Transcript Guard
        if (transcript == null || transcript.segments.isEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Transcript Required",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No transcript found for this project",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Complete the transcription stage first so Gemini can analyze spoken hooks and viral moments.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = onGoToTranscriptStage,
                        modifier = Modifier.testTag("go_to_transcript_stage_button")
                    ) {
                        Text("← Back to Transcript Stage")
                    }
                }
            }
            return
        }

        // Missing Gemini API Key Warning
        if (!hasGeminiKey) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .testTag("gemini_key_missing_card")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Key,
                        contentDescription = "API Key Warning",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Gemini API key not found",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Add your key in Settings → API Keys to discover viral clips.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onNavigateToSettings,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.testTag("settings_key_button")
                    ) {
                        Text("Settings")
                    }
                }
            }
        }

        // Error Banner
        if (analysisError != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .testTag("analysis_error_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AI Analysis Error",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = analysisError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = onDismissError,
                            modifier = Modifier.testTag("dismiss_analysis_error_button")
                        ) {
                            Text("Dismiss")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        if (analysisError.contains("Settings", ignoreCase = true) || !hasGeminiKey) {
                            Button(
                                onClick = onNavigateToSettings,
                                modifier = Modifier.testTag("error_go_to_settings_button")
                            ) {
                                Text("Go to Settings")
                            }
                        } else {
                            Button(
                                onClick = {
                                    onDismissError()
                                    onFindClips(requestedClipsCount, minClipLengthSeconds)
                                },
                                modifier = Modifier.testTag("retry_find_clips_button")
                            ) {
                                Text("Retry")
                            }
                        }
                    }
                }
            }
        }

        // Active Analysis Progress
        if (isAnalyzing) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .testTag("analysis_running_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Discovering Viral Moments...",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = progressText.ifBlank { "Analyzing transcript with Gemini Flash..." },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Configuration Card (Number selector & Length selector)
        if (isConfigVisible && !isAnalyzing) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .testTag("clip_discovery_setup_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Parameters",
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Viral Discovery Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (clipCandidates.isNotEmpty()) {
                            IconButton(onClick = { showConfigControls = false }) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Close Settings",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // How many clips? selector (1-30, default 10)
                    Text(
                        text = "How many clips? ($requestedClipsCount)",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "1",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Slider(
                            value = requestedClipsCount.toFloat(),
                            onValueChange = { requestedClipsCount = it.toInt() },
                            valueRange = 1f..30f,
                            steps = 28,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                                .testTag("clips_count_slider"),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        Text(
                            text = "30",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Minimum clip length selector (30s / 60s / 90s, default 60s)
                    Text(
                        text = "Minimum clip length:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf(30, 60, 90).forEach { seconds ->
                            val isSelected = minClipLengthSeconds == seconds
                            FilterChip(
                                selected = isSelected,
                                onClick = { minClipLengthSeconds = seconds },
                                label = { Text("${seconds}s") },
                                leadingIcon = {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                ),
                                modifier = Modifier.testTag("min_len_${seconds}s_chip")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Find Clips Button
                    Button(
                        onClick = {
                            showConfigControls = false
                            onFindClips(requestedClipsCount, minClipLengthSeconds)
                        },
                        enabled = hasGeminiKey && !isAnalyzing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("find_clips_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (clipCandidates.isEmpty()) "Find Clips" else "Re-discover Clips",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Summary Bar & "Analyze again" button when results exist
        if (clipCandidates.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = clipsStatusMessage ?: "${clipCandidates.size} clips discovered",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    val exportCount = clipCandidates.count { it.isIncludedInExport }
                    Text(
                        text = "$exportCount of ${clipCandidates.size} selected for export",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = { showConfigControls = !showConfigControls },
                    enabled = !isAnalyzing,
                    modifier = Modifier.testTag("analyze_again_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Analyze Again",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Analyze again")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Clip Cards sorted by score descending
            val sortedClips = remember(clipCandidates) {
                clipCandidates.sortedByDescending { it.score }
            }

            sortedClips.forEachIndexed { index, candidate ->
                ClipPlanCard(
                    candidate = candidate,
                    index = index + 1,
                    onToggleExport = { isIncluded -> onToggleExport(candidate.clipId, isIncluded) },
                    onCopyCaption = {
                        val tagsFormatted = candidate.hashtags.joinToString(" ") { tag ->
                            if (tag.startsWith("#")) tag else "#$tag"
                        }
                        val fullClipboardText = "${candidate.caption}\n\n$tagsFormatted".trim()
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Clip Caption & Hashtags", fullClipboardText)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Caption & hashtags copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Proceed to Stage 5 (Clip Plan) Button
            Button(
                onClick = onProceedToClipPlan,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("proceed_clip_plan_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    text = "Proceed to Clip Plan (Stage 5)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null
                )
            }
        }
    }
}

/**
 * Individual Clip Candidate Card according to Phase 6 requirements:
 * - Score badge (75+ green, 50-74 yellow, below 50 grey)
 * - Title
 * - Time ranges
 * - Hook sentence in quotes
 * - Virality reason
 * - Caption + hashtags (with copy button)
 * - Checkbox "include in export"
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClipPlanCard(
    candidate: ClipCandidate,
    index: Int,
    onToggleExport: (Boolean) -> Unit,
    onCopyCaption: () -> Unit
) {
    val score = candidate.score.toInt()

    // Score badge colors: 75+ green, 50-74 yellow, below 50 grey
    val (badgeBg, badgeTextColor) = when {
        score >= 75 -> Color(0xFF2E7D32) to Color.White // Green
        score >= 50 -> Color(0xFFF57F17) to Color.White // Yellow/Amber
        else -> Color(0xFF757575) to Color.White // Grey
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = if (candidate.isIncludedInExport) MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .testTag("clip_card_${candidate.clipId}")
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Top Row: Score Badge, Title, Checkbox
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Score Badge
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(badgeBg)
                        .testTag("clip_score_badge_${candidate.clipId}"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = score.toString(),
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            color = badgeTextColor
                        )
                        Text(
                            text = "SCORE",
                            fontWeight = FontWeight.Bold,
                            fontSize = 7.sp,
                            color = badgeTextColor.copy(alpha = 0.85f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                // Title & Duration
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.title.ifBlank { "Clip #$index" },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Time Ranges string e.g. "00:15 - 01:20"
                    val rangeText = formatSourceRanges(candidate.sourceRanges)
                    val totalDurationSec = candidate.sourceRanges.sumOf { (it.endMs - it.startMs) / 1000 }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            modifier = Modifier.size(13.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "$rangeText (${totalDurationSec}s)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Checkbox: "include in export"
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Checkbox(
                        checked = candidate.isIncludedInExport,
                        onCheckedChange = { onToggleExport(it) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary
                        ),
                        modifier = Modifier.testTag("include_export_${candidate.clipId}_checkbox")
                    )
                    Text(
                        text = "Export",
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = if (candidate.isIncludedInExport) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Hook Sentence in Quotes
            if (candidate.hookSentence.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "“",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            lineHeight = 24.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = candidate.hookSentence,
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Virality Reason
            if (candidate.viralityReason.isNotBlank()) {
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "⚡ Virality:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = candidate.viralityReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Suggested Caption & Hashtags with Copy Button
            if (candidate.caption.isNotBlank() || candidate.hashtags.isNotEmpty()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "POST CAPTION & HASHTAGS",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            IconButton(
                                onClick = onCopyCaption,
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("copy_clip_${candidate.clipId}_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy Caption",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        if (candidate.caption.isNotBlank()) {
                            Text(
                                text = candidate.caption,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        if (candidate.hashtags.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                candidate.hashtags.forEach { rawTag ->
                                    val tag = if (rawTag.startsWith("#")) rawTag else "#$rawTag"
                                    Text(
                                        text = tag,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSourceRanges(ranges: List<SourceRange>): String {
    if (ranges.isEmpty()) return "00:00 - 00:00"
    return ranges.joinToString(", ") { r ->
        "${formatMsToTimestamp(r.startMs)} - ${formatMsToTimestamp(r.endMs)}"
    }
}

private fun formatMsToTimestamp(ms: Long): String {
    val totalSec = ms / 1000
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
