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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.clipgenius.ai.input.VideoInputHandler
import com.clipgenius.ai.state.AudioChunk
import com.clipgenius.ai.state.AudioChunkManifest
import com.clipgenius.ai.state.ChunkStatus
import com.clipgenius.ai.state.Project
import com.clipgenius.ai.state.ProjectTranscript
import com.clipgenius.ai.state.TranscriptSegment
import com.clipgenius.ai.ui.theme.StageCompleted
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@Composable
fun TranscriptStageContent(
    project: Project?,
    audioManifest: AudioChunkManifest?,
    transcript: ProjectTranscript?,
    isWorkerProcessing: Boolean,
    isWorkerPaused: Boolean,
    transcriptionError: String?,
    authError: String?,
    quotaError: String?,
    hasDeepgramKey: Boolean,
    hasGeminiKey: Boolean,
    onStartTranscription: (useGeminiFallback: Boolean) -> Unit,
    onRetryChunkClick: (chunkIndex: Int) -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onDismissError: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onGoToAudioStage: () -> Unit,
    onProceedToAiAnalysis: () -> Unit,
    onUpdateSegmentText: (segmentId: String, newText: String) -> Unit,
    onRenameSpeakerGlobally: (oldSpeaker: String, newSpeaker: String) -> Unit,
    onExportSrt: (onSuccess: (String) -> Unit, onError: (String) -> Unit) -> Unit
) {
    // 1. Guard: Check if transcript is already produced
    val hasTranscript = transcript != null && transcript.segments.isNotEmpty()

    if (hasTranscript) {
        // Phase 5: Transcript Screen & Interactive Video Preview Player
        Phase5TranscriptViewer(
            project = project,
            transcript = transcript,
            onUpdateSegmentText = onUpdateSegmentText,
            onRenameSpeakerGlobally = onRenameSpeakerGlobally,
            onExportSrt = onExportSrt,
            onProceedToAiAnalysis = onProceedToAiAnalysis,
            onRetranscribe = { onStartTranscription(false) }
        )
    } else {
        // Not completed yet - Transcription Pipeline Setup / Execution View
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Stage Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Article,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Stage 3: Transcript",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Deepgram Nova-3 word timestamps & speaker diarization",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Guard: Audio stage incomplete
            if (audioManifest == null || !audioManifest.isExtractionComplete || audioManifest.chunks.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "No transcript yet. Complete the Audio stage first.",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Please complete audio extraction and chunking before transcribing.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onGoToAudioStage,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.testTag("go_to_audio_stage_button")
                        ) {
                            Icon(imageVector = Icons.Default.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Go to Audio Stage")
                        }
                    }
                }
            } else {
                // Audio is ready, display either transcription queue or setup card
                if (isWorkerProcessing || audioManifest.chunks.any { it.status != ChunkStatus.QUEUED }) {
                    TranscriptionProgressQueueView(
                        audioManifest = audioManifest,
                        isWorkerProcessing = isWorkerProcessing,
                        isWorkerPaused = isWorkerPaused,
                        authError = authError,
                        quotaError = quotaError,
                        transcriptionError = transcriptionError,
                        onPauseClick = onPauseClick,
                        onResumeClick = onResumeClick,
                        onRetryChunkClick = onRetryChunkClick,
                        onDismissError = onDismissError,
                        onNavigateToSettings = onNavigateToSettings,
                        onFallbackToGemini = { onStartTranscription(true) }
                    )
                } else {
                    TranscriptionSetupCard(
                        audioManifest = audioManifest,
                        hasDeepgramKey = hasDeepgramKey,
                        hasGeminiKey = hasGeminiKey,
                        transcriptionError = transcriptionError,
                        onStartDeepgram = { onStartTranscription(false) },
                        onStartGemini = { onStartTranscription(true) },
                        onNavigateToSettings = onNavigateToSettings,
                        onDismissError = onDismissError
                    )
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Phase 5 Main Transcript Viewer
 * Includes:
 * 1. Pinned Video Preview with ExoPlayer/Media3 & Seek
 * 2. Search bar with query match count & highlighting
 * 3. Stats bar (segments, words, duration, speakers)
 * 4. Controls toolbar (Follow playback, Edit mode, Rename speaker, Export .srt)
 * 5. Scrollable transcript list (MM:SS, speaker chip, text, edit badge)
 * 6. Edit segment dialog (Text edits never alter timing)
 * 7. Speaker rename dialog
 * 8. Export .srt action with path confirmation
 * 9. Continue to AI Analysis button
 */
@Composable
private fun Phase5TranscriptViewer(
    project: Project?,
    transcript: ProjectTranscript,
    onUpdateSegmentText: (segmentId: String, newText: String) -> Unit,
    onRenameSpeakerGlobally: (oldSpeaker: String, newSpeaker: String) -> Unit,
    onExportSrt: (onSuccess: (String) -> Unit, onError: (String) -> Unit) -> Unit,
    onProceedToAiAnalysis: () -> Unit,
    onRetranscribe: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Video Player Playback State
    var isPlaying by remember { mutableStateOf(false) }
    var currentPlaybackMs by remember { mutableLongStateOf(0L) }
    var videoDurationMs by remember { mutableLongStateOf(project?.sourceMedia?.durationMs ?: 0L) }
    var pendingSeekMs by remember { mutableStateOf<Long?>(null) }

    // Phase 5 UI Controls
    var searchQuery by remember { mutableStateOf("") }
    var isEditMode by remember { mutableStateOf(false) }
    var followPlayback by remember { mutableStateOf(true) }

    // Dialog States
    var editingSegment by remember { mutableStateOf<TranscriptSegment?>(null) }
    var showRenameSpeakerDialog by remember { mutableStateOf(false) }
    var exportStatusMessage by remember { mutableStateOf<String?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }

    // ExoPlayer initialization
    val videoPath = project?.sourceVideoPath.orEmpty()
    val videoFile = remember(videoPath) { File(videoPath) }

    val exoPlayer = remember(videoPath) {
        ExoPlayer.Builder(context).build().apply {
            if (videoFile.exists()) {
                val mediaItem = MediaItem.fromUri(Uri.fromFile(videoFile))
                setMediaItem(mediaItem)
                prepare()
            }
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    if (exoPlayer.duration > 0) {
                        videoDurationMs = exoPlayer.duration
                    }
                }
            }
        }
        exoPlayer.addListener(listener)

        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Polling player position
    LaunchedEffect(exoPlayer) {
        while (true) {
            if (exoPlayer.isPlaying) {
                currentPlaybackMs = exoPlayer.currentPosition
                if (exoPlayer.duration > 0) {
                    videoDurationMs = exoPlayer.duration
                }
            }
            delay(150)
        }
    }

    // Handle row tap seek requests
    LaunchedEffect(pendingSeekMs) {
        val target = pendingSeekMs
        if (target != null) {
            exoPlayer.seekTo(target)
            currentPlaybackMs = target
            exoPlayer.play()
            pendingSeekMs = null
        }
    }

    // Filtered Segments for Search
    val filteredSegments = remember(transcript.segments, searchQuery) {
        if (searchQuery.isBlank()) {
            transcript.segments
        } else {
            transcript.segments.filter { seg ->
                seg.text.contains(searchQuery, ignoreCase = true) ||
                        seg.speakerLabel?.contains(searchQuery, ignoreCase = true) == true ||
                        seg.words.any { it.word.contains(searchQuery, ignoreCase = true) }
            }
        }
    }

    // Active Segment index for "Follow playback"
    val activeSegmentIndex = remember(currentPlaybackMs, transcript.segments) {
        transcript.segments.indexOfFirst { seg ->
            currentPlaybackMs >= seg.startMs && currentPlaybackMs < seg.endMs
        }
    }

    // Auto-scroll list when "Follow playback" is enabled
    LaunchedEffect(activeSegmentIndex, followPlayback) {
        if (followPlayback && activeSegmentIndex >= 0) {
            val targetListIndex = if (searchQuery.isBlank()) {
                activeSegmentIndex
            } else {
                val activeSeg = transcript.segments.getOrNull(activeSegmentIndex)
                filteredSegments.indexOf(activeSeg)
            }
            if (targetListIndex >= 0) {
                coroutineScope.launch {
                    listState.animateScrollToItem(targetListIndex)
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag("transcript_viewer_container")
    ) {
        // 1. VIDEO PREVIEW WITH SEEK (Pinned Top Player)
        VideoPreviewCard(
            exoPlayer = exoPlayer,
            videoFileExists = videoFile.exists(),
            isPlaying = isPlaying,
            currentPositionMs = currentPlaybackMs,
            totalDurationMs = videoDurationMs,
            onPlayPauseClick = {
                if (exoPlayer.isPlaying) {
                    exoPlayer.pause()
                } else {
                    exoPlayer.play()
                }
            },
            onSeekTo = { newPosMs ->
                exoPlayer.seekTo(newPosMs)
                currentPlaybackMs = newPosMs
            }
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 2. SEARCH BAR & MATCH COUNT
        SearchBarWithMatches(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            matchCount = if (searchQuery.isNotBlank()) filteredSegments.size else null,
            onClear = { searchQuery = "" }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 3. STATS BAR
        TranscriptStatsRow(
            totalSegments = transcript.segments.size,
            totalWords = transcript.totalWords,
            durationCoveredMs = videoDurationMs.takeIf { it > 0 }
                ?: (transcript.segments.lastOrNull()?.endMs ?: 0L),
            totalSpeakers = transcript.totalSpeakers
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 4. ACTION CONTROLS TOOLBAR
        TranscriptToolbar(
            followPlayback = followPlayback,
            onToggleFollow = { followPlayback = !followPlayback },
            isEditMode = isEditMode,
            onToggleEditMode = { isEditMode = !isEditMode },
            onRenameSpeakerClick = { showRenameSpeakerDialog = true },
            onExportSrtClick = {
                onExportSrt(
                    { path ->
                        exportStatusMessage = "Successfully exported to:\n$path"
                        showExportDialog = true
                    },
                    { error ->
                        exportStatusMessage = "Export failed: $error"
                        showExportDialog = true
                    }
                )
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // 5. SCROLLABLE TRANSCRIPT SEGMENTS LIST
        Box(modifier = Modifier.weight(1f)) {
            if (filteredSegments.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "No segments match \"$searchQuery\"" else "No segments found",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("transcript_segments_list")
                ) {
                    itemsIndexed(
                        items = filteredSegments,
                        key = { _, item -> item.id }
                    ) { _, segment ->
                        val isCurrentlyPlaying = currentPlaybackMs >= segment.startMs && currentPlaybackMs < segment.endMs

                        TranscriptSegmentRow(
                            segment = segment,
                            isCurrentlyPlaying = isCurrentlyPlaying,
                            isEditMode = isEditMode,
                            searchQuery = searchQuery,
                            onRowClick = {
                                if (isEditMode) {
                                    editingSegment = segment
                                } else {
                                    pendingSeekMs = segment.startMs
                                }
                            },
                            onEditClick = {
                                editingSegment = segment
                            },
                            onTimeBadgeClick = {
                                pendingSeekMs = segment.startMs
                            }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        // 6. STAGE WIRING: "Continue to AI Analysis →" Button
                        Button(
                            onClick = onProceedToAiAnalysis,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("continue_to_ai_analysis_button")
                        ) {
                            Text("Continue to AI Analysis (Stage 4)", fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(imageVector = Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    // DIALOG: Edit Segment Text
    editingSegment?.let { seg ->
        EditSegmentDialog(
            segment = seg,
            onDismiss = { editingSegment = null },
            onSave = { updatedText ->
                onUpdateSegmentText(seg.id, updatedText)
                editingSegment = null
            }
        )
    }

    // DIALOG: Rename Speaker Globally
    if (showRenameSpeakerDialog) {
        val existingSpeakers = remember(transcript.segments) {
            transcript.segments.mapNotNull { it.speakerLabel }.distinct()
        }
        RenameSpeakerDialog(
            speakers = existingSpeakers,
            onDismiss = { showRenameSpeakerDialog = false },
            onRename = { oldSpeaker, newSpeaker ->
                onRenameSpeakerGlobally(oldSpeaker, newSpeaker)
                showRenameSpeakerDialog = false
            }
        )
    }

    // DIALOG: Export Confirmation
    if (showExportDialog && exportStatusMessage != null) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("SRT Export") },
            text = { Text(exportStatusMessage ?: "") },
            confirmButton = {
                TextButton(
                    onClick = { showExportDialog = false },
                    modifier = Modifier.testTag("export_dialog_ok_button")
                ) {
                    Text("OK")
                }
            }
        )
    }
}

/**
 * Small Video Preview Player using Media3 PlayerView
 */
@Composable
private fun VideoPreviewCard(
    exoPlayer: ExoPlayer,
    videoFileExists: Boolean,
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    onPlayPauseClick: () -> Unit,
    onSeekTo: (Long) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("video_preview_card")
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Player surface
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (videoFileExists) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = false
                            }
                        },
                        update = { playerView ->
                            playerView.player = exoPlayer
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.height(4.dp))
                        Text("Video preview file not found", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Controls & Seek Bar Under Player
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                // Slider
                val sliderValue = if (totalDurationMs > 0) {
                    (currentPositionMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }

                Slider(
                    value = sliderValue,
                    onValueChange = { frac ->
                        val targetMs = (frac * totalDurationMs).toLong()
                        onSeekTo(targetMs)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .testTag("video_seek_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = MaterialTheme.colorScheme.primary,
                        activeTrackColor = MaterialTheme.colorScheme.primary
                    )
                )

                // Playback row: Current Time / Total Time & Transport Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Time Label
                    Text(
                        text = "${formatMmSs(currentPositionMs)} / ${formatMmSs(totalDurationMs)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("video_playback_time_text")
                    )

                    // Transport controls
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                val newPos = (currentPositionMs - 5000L).coerceAtLeast(0L)
                                onSeekTo(newPos)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(imageVector = Icons.Default.FastRewind, contentDescription = "Rewind 5s", modifier = Modifier.size(18.dp))
                        }

                        IconButton(
                            onClick = onPlayPauseClick,
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .testTag("video_play_pause_button")
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        IconButton(
                            onClick = {
                                val newPos = (currentPositionMs + 5000L).coerceAtMost(totalDurationMs)
                                onSeekTo(newPos)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(imageVector = Icons.Default.FastForward, contentDescription = "Forward 5s", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Search Bar with Match Counter
 */
@Composable
private fun SearchBarWithMatches(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int?,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text("Search transcript words or speakers...") },
        leadingIcon = {
            Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (matchCount != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(
                            text = "$matchCount ${if (matchCount == 1) "match" else "matches"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (query.isNotBlank()) {
                    IconButton(onClick = onClear) {
                        Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(18.dp))
                    }
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("transcript_search_input")
    )
}

/**
 * Small Stats Row (Segments, Words, Duration, Speakers)
 */
@Composable
private fun TranscriptStatsRow(
    totalSegments: Int,
    totalWords: Int,
    durationCoveredMs: Long,
    totalSpeakers: Int
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatItem(label = "Segments", value = totalSegments.toString())
            StatDivider()
            StatItem(label = "Words", value = totalWords.toString())
            StatDivider()
            StatItem(label = "Duration", value = formatMmSs(durationCoveredMs))
            StatDivider()
            StatItem(label = "Speakers", value = totalSpeakers.toString())
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(20.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

/**
 * Action Toolbar: Follow playback, Edit mode, Rename Speaker, Export .srt
 */
@Composable
private fun TranscriptToolbar(
    followPlayback: Boolean,
    onToggleFollow: () -> Unit,
    isEditMode: Boolean,
    onToggleEditMode: () -> Unit,
    onRenameSpeakerClick: () -> Unit,
    onExportSrtClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Follow Playback Toggle
        FilterChip(
            selected = followPlayback,
            onClick = onToggleFollow,
            label = { Text("Follow", fontSize = 12.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.primary
            ),
            modifier = Modifier.testTag("follow_playback_chip")
        )

        // Edit Mode Toggle
        FilterChip(
            selected = isEditMode,
            onClick = onToggleEditMode,
            label = { Text(if (isEditMode) "Editing" else "Edit", fontSize = 12.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer
            ),
            modifier = Modifier.testTag("edit_mode_chip")
        )

        Spacer(modifier = Modifier.weight(1f))

        // Rename Speaker Action
        OutlinedButton(
            onClick = onRenameSpeakerClick,
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.testTag("rename_speaker_button")
        ) {
            Icon(imageVector = Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Rename", fontSize = 12.sp)
        }

        // Export .srt Action
        Button(
            onClick = onExportSrtClick,
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            modifier = Modifier.testTag("export_srt_button")
        ) {
            Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(".srt", fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Individual Transcript Row with Start Time (MM:SS), colored Speaker Chip, Segment Text, and Edit Indicator
 */
@Composable
private fun TranscriptSegmentRow(
    segment: TranscriptSegment,
    isCurrentlyPlaying: Boolean,
    isEditMode: Boolean,
    searchQuery: String,
    onRowClick: () -> Unit,
    onEditClick: () -> Unit,
    onTimeBadgeClick: () -> Unit
) {
    val speakerColors = remember(segment.speakerLabel) {
        getSpeakerBadgeColors(segment.speakerLabel ?: "Speaker 1")
    }

    val cardBorder = if (isCurrentlyPlaying) {
        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
    } else {
        Modifier
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrentlyPlaying) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .then(cardBorder)
            .clickable { onRowClick() }
            .testTag("transcript_segment_${segment.id}")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Header Row: Time Badge + Speaker Label Chip + Edit Marker
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Start Time Chip (Tapping seeks preview)
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.clickable { onTimeBadgeClick() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(2.dp))
                            Text(
                                text = formatMmSs(segment.startMs),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Colored Speaker Chip
                    Surface(
                        color = speakerColors.second,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = segment.speakerLabel ?: "Speaker 1",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = speakerColors.first,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }

                    // Edited Marker Dot
                    if (segment.isEdited) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.tertiary)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "edited",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                // Edit Button in edit mode
                if (isEditMode) {
                    IconButton(
                        onClick = onEditClick,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit segment text",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Segment Text with Search Highlighting
            val annotatedText = buildAnnotatedString {
                val fullText = segment.text
                if (searchQuery.isBlank()) {
                    append(fullText)
                } else {
                    var startIndex = 0
                    while (startIndex < fullText.length) {
                        val index = fullText.indexOf(searchQuery, startIndex, ignoreCase = true)
                        if (index < 0) {
                            append(fullText.substring(startIndex))
                            break
                        }
                        if (index > startIndex) {
                            append(fullText.substring(startIndex, index))
                        }
                        withStyle(
                            style = SpanStyle(
                                background = Color(0xFFFFF176),
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        ) {
                            append(fullText.substring(index, index + searchQuery.length))
                        }
                        startIndex = index + searchQuery.length
                    }
                }
            }

            Text(
                text = annotatedText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = 20.sp
            )
        }
    }
}

/**
 * Dialog for Editing Segment Text
 * Timestamps (startMs/endMs) are never modified through text editing!
 */
@Composable
private fun EditSegmentDialog(
    segment: TranscriptSegment,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var editedText by remember(segment.text) { mutableStateOf(segment.text) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    text = "Edit Segment Text",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${segment.speakerLabel ?: "Speaker 1"} • ${formatMmSs(segment.startMs)} - ${formatMmSs(segment.endMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column {
                // Text edits never alter timing.
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    label = { Text("Transcript Text") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .testTag("edit_segment_text_field"),
                    maxLines = 6,
                    shape = RoundedCornerShape(12.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Note: Timing (${formatMmSs(segment.startMs)} - ${formatMmSs(segment.endMs)}) remains synchronized.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(editedText) },
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("save_segment_edit_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for Globally Renaming a Speaker Label
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RenameSpeakerDialog(
    speakers: List<String>,
    onDismiss: () -> Unit,
    onRename: (oldSpeaker: String, newSpeaker: String) -> Unit
) {
    var selectedSpeaker by remember { mutableStateOf(speakers.firstOrNull() ?: "Speaker 1") }
    var newSpeakerName by remember { mutableStateOf("") }
    var expandedDropdown by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Rename Speaker Globally",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Renames all segments tagged with this speaker across the entire project.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Select Existing Speaker
                ExposedDropdownMenuBox(
                    expanded = expandedDropdown,
                    onExpandedChange = { expandedDropdown = it }
                ) {
                    OutlinedTextField(
                        value = selectedSpeaker,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Current Speaker") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedDropdown) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                        shape = RoundedCornerShape(10.dp)
                    )
                    ExposedDropdownMenu(
                        expanded = expandedDropdown,
                        onDismissRequest = { expandedDropdown = false }
                    ) {
                        speakers.forEach { speaker ->
                            DropdownMenuItem(
                                text = { Text(speaker) },
                                onClick = {
                                    selectedSpeaker = speaker
                                    expandedDropdown = false
                                }
                            )
                        }
                    }
                }

                // New Name Input
                OutlinedTextField(
                    value = newSpeakerName,
                    onValueChange = { newSpeakerName = it },
                    label = { Text("New Name (e.g. Host, Alex, Guest)") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_speaker_name_field")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onRename(selectedSpeaker, newSpeakerName) },
                enabled = newSpeakerName.isNotBlank() && newSpeakerName.trim() != selectedSpeaker,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.testTag("confirm_rename_speaker_button")
            ) {
                Text("Rename All")
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
 * Returns distinct accessible colors for a given speaker label.
 */
private fun getSpeakerBadgeColors(speakerLabel: String): Pair<Color, Color> {
    val hash = Math.abs(speakerLabel.hashCode()) % 5
    return when (hash) {
        0 -> Color(0xFF006A60) to Color(0xFFCCE8E3) // Teal
        1 -> Color(0xFF6750A4) to Color(0xFFEADDFF) // Purple
        2 -> Color(0xFF984061) to Color(0xFFFFD9E2) // Berry
        3 -> Color(0xFF00639B) to Color(0xFFD1E4FF) // Blue
        else -> Color(0xFF7D5700) to Color(0xFFFFDEA8) // Amber
    }
}

private fun formatMmSs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

// -------------------------------------------------------------
// TRANSCRIPTION QUEUE & SETUP (Carried over from Phase 4)
// -------------------------------------------------------------

@Composable
private fun TranscriptionSetupCard(
    audioManifest: AudioChunkManifest,
    hasDeepgramKey: Boolean,
    hasGeminiKey: Boolean,
    transcriptionError: String?,
    onStartDeepgram: () -> Unit,
    onStartGemini: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onDismissError: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Article,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Ready to Transcribe",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "${audioManifest.chunks.size} audio chunks prepared (${VideoInputHandler.formatDuration(audioManifest.totalDurationMs)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Pipeline Features:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Deepgram Nova-3 model with smart format & punctuation\n" +
                                "• Word-level timestamps for precise sentence-level cuts\n" +
                                "• Automatic speaker diarization (Speaker 1, Speaker 2...)\n" +
                                "• Parallel worker queue (up to 3 concurrent chunks)\n" +
                                "• 60s overlap boundary deduplication",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (!hasDeepgramKey) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Deepgram API key not found",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Please add your Deepgram API key in Settings → API Keys to transcribe using Nova-3.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = onNavigateToSettings,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("open_settings_for_deepgram_button")
                        ) {
                            Icon(imageVector = Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Open Settings")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedButton(
                    onClick = onStartGemini,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("start_gemini_fallback_button")
                ) {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Use Gemini direct fallback")
                }
            } else {
                Button(
                    onClick = onStartDeepgram,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("start_transcription_button")
                ) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.Article, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Start Transcription (Deepgram Nova-3)", fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = onStartGemini,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("start_gemini_fallback_button")
                ) {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Use Gemini direct fallback (alternative)")
                }
            }
        }
    }
}

@Composable
private fun TranscriptionProgressQueueView(
    audioManifest: AudioChunkManifest,
    isWorkerProcessing: Boolean,
    isWorkerPaused: Boolean,
    authError: String?,
    quotaError: String?,
    transcriptionError: String?,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onRetryChunkClick: (Int) -> Unit,
    onDismissError: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onFallbackToGemini: () -> Unit
) {
    val totalChunks = audioManifest.chunks.size
    val doneChunks = audioManifest.chunks.count { it.status == ChunkStatus.DONE }
    val progress = if (totalChunks > 0) doneChunks.toFloat() / totalChunks.toFloat() else 0f

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Transcribing Audio",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Chunk $doneChunks of $totalChunks completed (${(progress * 100).toInt()}%)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isWorkerPaused) {
                        OutlinedButton(onClick = onResumeClick, shape = RoundedCornerShape(8.dp)) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Resume")
                        }
                    } else if (isWorkerProcessing) {
                        OutlinedButton(onClick = onPauseClick, shape = RoundedCornerShape(8.dp)) {
                            Icon(imageVector = Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pause")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Chunk rows
            audioManifest.chunks.forEach { chunk ->
                ChunkRow(
                    chunk = chunk,
                    onRetryClick = { onRetryChunkClick(chunk.index) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ChunkRow(
    chunk: AudioChunk,
    onRetryClick: () -> Unit
) {
    val durationLabel = formatMmSs(chunk.endMs - chunk.startMs)

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(
                        when (chunk.status) {
                            ChunkStatus.DONE -> StageCompleted.copy(alpha = 0.2f)
                            ChunkStatus.PROCESSING -> MaterialTheme.colorScheme.primaryContainer
                            ChunkStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                            ChunkStatus.QUEUED -> MaterialTheme.colorScheme.surface
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                when (chunk.status) {
                    ChunkStatus.DONE -> Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = StageCompleted, modifier = Modifier.size(18.dp))
                    ChunkStatus.PROCESSING -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    ChunkStatus.FAILED -> Icon(imageVector = Icons.Default.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    ChunkStatus.QUEUED -> Icon(imageVector = Icons.AutoMirrored.Filled.Article, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Chunk #${chunk.index + 1} ($durationLabel)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when (chunk.status) {
                        ChunkStatus.QUEUED -> "Queued for transcription"
                        ChunkStatus.PROCESSING -> "Uploading & transcribing..."
                        ChunkStatus.DONE -> "Transcribed & timestamps extracted"
                        ChunkStatus.FAILED -> chunk.errorMessage ?: "Failed"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (chunk.status == ChunkStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (chunk.status == ChunkStatus.FAILED) {
                OutlinedButton(
                    onClick = onRetryClick,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("retry_chunk_button_${chunk.index}")
                ) {
                    Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Retry")
                }
            }
        }
    }
}
