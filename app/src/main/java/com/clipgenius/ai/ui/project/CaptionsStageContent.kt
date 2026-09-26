package com.clipgenius.ai.ui.project

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HorizontalSplit
import androidx.compose.material.icons.filled.LocalActivity
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clipgenius.ai.captions.CaptionGenerator
import com.clipgenius.ai.state.CaptionCue
import com.clipgenius.ai.state.CaptionPreset
import com.clipgenius.ai.state.Project
import com.clipgenius.ai.state.VerificationStatus
import com.clipgenius.ai.state.VerifiedClip
import com.clipgenius.ai.ui.components.LiveCaptionPlayer
import java.io.File
import java.util.Locale

/**
 * Phase 11 Caption & Typography Timeline Editor Screen.
 * Features:
 * - 9:16 portrait video player with real-time double-stroked subtitles
 * - Safe area guidelines overlay toggles (auto clamps positions and warns on exceed)
 * - 4 ready premium presets (TikTok, Neon Pop, Cinema, Minimal)
 * - Timeline Cue List allowing split-on-word selection & multi-cue merging
 * - Bullet-proof XML/JSON presets importer & downloads exporter
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CaptionsStageContent(
    project: Project?,
    verifiedClips: List<VerifiedClip>,
    allPresets: List<CaptionPreset>,
    activeClipCues: List<CaptionCue>,
    activeCaptionClipId: String?,
    onSelectActiveClip: (String) -> Unit,
    onUpdateCueText: (clipId: String, cueId: String, newText: String) -> Unit,
    onSplitCue: (clipId: String, cueId: String, wordIndex: Int) -> Unit,
    onMergeCue: (clipId: String, cueId: String) -> Unit,
    onSelectPreset: (clipId: String, presetId: String) -> Unit,
    onToggleBurnIn: (clipId: String, burn: Boolean) -> Unit,
    onImportPreset: (jsonContent: String, onWarnings: (List<String>) -> Unit) -> Unit,
    onExportPreset: (CaptionPreset, onSuccess: (String) -> Unit) -> Unit,
    onDeletePreset: (String) -> Unit,
    onMarkStageComplete: () -> Unit,
    onProceedToRender: () -> Unit
) {
    val context = LocalContext.current

    val exportableClips = remember(verifiedClips) {
        verifiedClips.filter {
            (it.status == VerificationStatus.VERIFIED || it.isManuallyEdited) && it.isIncludedInExport
        }
    }

    // Auto-select first clip if none is active
    LaunchedEffect(activeCaptionClipId, exportableClips) {
        if (activeCaptionClipId == null && exportableClips.isNotEmpty()) {
            onSelectActiveClip(exportableClips.first().clipId)
        }
    }

    val activeClip = remember(exportableClips, activeCaptionClipId) {
        exportableClips.find { it.clipId == activeCaptionClipId } ?: exportableClips.firstOrNull()
    }

    val activePreset = remember(allPresets, activeClip) {
        allPresets.find { it.id == activeClip?.captionPresetId }
            ?: allPresets.find { it.id == "preset_bold_tiktok" }
            ?: CaptionGenerator.PRESET_BOLD_TIKTOK
    }

    var showGuides by remember { mutableStateOf(true) }
    var warningLogs by remember { mutableStateOf<List<String>>(emptyList()) }

    // JSON file picker for importing presets
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val content = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader().readText()
                }
                if (!content.isNullOrBlank()) {
                    onImportPreset(content) { logs ->
                        warningLogs = logs
                        if (logs.isNotEmpty()) {
                            Toast.makeText(context, "Preset imported with ${logs.size} safe fixes", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(context, "Preset style imported successfully!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Failed to read preset: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // --- 1. HERO HEADER ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("captions_hero_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Subtitles,
                        contentDescription = "Animated Captions",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Stage 7: Subtitle Presets & Editor",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = "Word-by-word interactive karaoke sync with customizable safe limits.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (activeClip == null) {
            item {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("No clips ready for captions. Return to stage 6.", style = MaterialTheme.typography.bodyMedium)
                }
            }
            return@LazyColumn
        }

        // --- 2. CLIP SELECTOR TAB STRIP ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                exportableClips.forEachIndexed { idx, item ->
                    val isActive = item.clipId == activeClip.clipId
                    Surface(
                        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .clickable { onSelectActiveClip(item.clipId) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "Clip ${idx + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }

        // --- 3. LIVE 9:16 PREVIEW & CONFIG zone ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(14.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    // Title info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = activeClip.originalCandidate.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Safe Lines", style = MaterialTheme.typography.labelSmall)
                            Spacer(modifier = Modifier.width(4.dp))
                            Switch(
                                checked = showGuides,
                                onCheckedChange = { showGuides = it },
                                modifier = Modifier.scale(0.8f).testTag("guides_toggle")
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Player with live overlays
                    val videoPathToUse = activeClip.verticalDraftPath ?: activeClip.draftVideoPath ?: ""
                    LiveCaptionPlayer(
                        videoPath = videoPathToUse,
                        cues = activeClipCues,
                        preset = activePreset,
                        showGuides = showGuides,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Settings: Burn-in vs SRT, Import, Export
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Burn in captions switch
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = activeClip.burnCaptions,
                                onCheckedChange = { onToggleBurnIn(activeClip.clipId, it) },
                                modifier = Modifier.testTag("burn_in_toggle")
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Column {
                                Text("Burn into video", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text("TikTok subtitle style", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        // Presets Share buttons
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { importLauncher.launch("application/json") },
                                modifier = Modifier.height(36.dp).testTag("import_preset_button")
                            ) {
                                Icon(imageVector = Icons.Default.CloudUpload, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Import", fontSize = 11.sp)
                            }

                            OutlinedButton(
                                onClick = {
                                    onExportPreset(activePreset) { exportedPath ->
                                        Toast.makeText(context, "Saved style to: $exportedPath", Toast.LENGTH_LONG).show()
                                    }
                                },
                                modifier = Modifier.height(36.dp).testTag("export_preset_button")
                            ) {
                                Icon(imageVector = Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Export", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }

        // --- 4. PRESETS SELECTOR LIST ---
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Select Subtitle Style Preset",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(6.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    allPresets.forEach { p ->
                        val isSelected = p.id == activePreset.id
                        Surface(
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .clickable { onSelectPreset(activeClip.clipId, p.id) }
                                .padding(vertical = 4.dp)
                                .border(
                                    width = if (isSelected) 1.5.dp else 0.dp,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .testTag("preset_select_${p.id}")
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = p.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (p.isImported) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Surface(
                                        color = Color.Yellow,
                                        shape = RoundedCornerShape(4.dp),
                                        modifier = Modifier.padding(2.dp)
                                    ) {
                                        Text("imported", fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
                                    }

                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete",
                                        tint = Color.Red,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { onDeletePreset(p.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // --- 5. TIMELINE CUES LIST ZONE ---
        item {
            Text(
                text = "Interactive Subtitles Timeline",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (activeClipCues.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No subtitles cues. Loading...", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            itemsIndexed(activeClipCues) { idx, cue ->
                CaptionCueCard(
                    cue = cue,
                    index = idx + 1,
                    isLast = idx == activeClipCues.size - 1,
                    onUpdateText = { newText -> onUpdateCueText(activeClip.clipId, cue.id, newText) },
                    onSplit = { wordIdx -> onSplitCue(activeClip.clipId, cue.id, wordIdx) },
                    onMerge = { onMergeCue(activeClip.clipId, cue.id) }
                )
            }
        }

        // --- 6. COMPLETION ACTIONS BAR ---
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("captions_stage_completion_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Captions Config Complete?",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Save your adjustments and proceed to the high-fidelity video rendering export stage.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Button(
                            onClick = {
                                onMarkStageComplete()
                                onProceedToRender()
                            },
                            modifier = Modifier.testTag("proceed_to_render_button")
                        ) {
                            Text("Render (Stage 8)")
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Individual Caption Cue Timeline Editor Card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptionCueCard(
    cue: CaptionCue,
    index: Int,
    isLast: Boolean,
    onUpdateText: (String) -> Unit,
    onSplit: (Int) -> Unit,
    onMerge: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var textValue by remember { mutableStateOf(cue.text) }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, Color.LightGray.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .testTag("caption_cue_card_${cue.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header row with timings and badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "#$index",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val startSec = String.format(Locale.US, "%.1fs", cue.cueStartMs / 1000f)
                    val endSec = String.format(Locale.US, "%.1fs", cue.cueEndMs / 1000f)
                    val durSec = String.format(Locale.US, "%.1fs", (cue.cueEndMs - cue.cueStartMs) / 1000f)
                    Text(
                        text = "$startSec - $endSec ($durSec)",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (cue.isEdited) {
                        Surface(
                            color = Color(0xFFFFF9C4),
                            shape = RoundedCornerShape(4.dp),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        ) {
                            Text("edited", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF57F17), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                        }
                    }

                    IconButton(onClick = { isEditing = !isEditing }, modifier = Modifier.size(24.dp).testTag("edit_cue_btn_${cue.id}")) {
                        Icon(imageVector = Icons.Default.Edit, contentDescription = "Edit Text", modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Subtitle input TextField
            if (isEditing) {
                OutlinedTextField(
                    value = textValue,
                    onValueChange = {
                        textValue = it
                        onUpdateText(it)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cue_textfield_${cue.id}"),
                    textStyle = TextStyle(fontSize = 13.sp),
                    maxLines = 2
                )
            } else {
                Text(
                    text = cue.text,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Interactive Split on specific word selection
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Tap word to split cue from that point:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp
                )
                Spacer(modifier = Modifier.height(4.dp))

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    cue.wordTimings.forEachIndexed { wordIdx, item ->
                        if (wordIdx > 0) { // Cannot split on the first word
                            Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(4.dp),
                                modifier = Modifier
                                    .clickable { onSplit(wordIdx) }
                                    .padding(vertical = 2.dp)
                                    .testTag("split_word_${cue.id}_$wordIdx")
                            ) {
                                Text(
                                    text = item.word,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Merge timeline controls
            if (!isLast) {
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = Color.LightGray.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onMerge,
                        modifier = Modifier
                            .height(30.dp)
                            .testTag("merge_cue_btn_${cue.id}")
                    ) {
                        Icon(imageVector = Icons.Default.Merge, contentDescription = null, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Merge with next cue", fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

// Extension to scale modifier
private fun Modifier.scale(scale: Float) = this.size((48 * scale).dp)
