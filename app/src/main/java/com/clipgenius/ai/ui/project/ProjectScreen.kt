package com.clipgenius.ai.ui.project

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MovieFilter
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.clipgenius.ai.ui.components.ActivityLogDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.clipgenius.ai.audio.AudioExtractor
import com.clipgenius.ai.input.VideoInputHandler
import com.clipgenius.ai.state.AudioChunk
import com.clipgenius.ai.state.AudioChunkManifest
import com.clipgenius.ai.state.ChunkStatus
import com.clipgenius.ai.state.StageInfo
import com.clipgenius.ai.state.LayoutType
import com.clipgenius.ai.ui.components.ClipGeniusProgressDialog
import com.clipgenius.ai.ui.theme.StageCompleted
import com.clipgenius.ai.ui.theme.StageInactive

data class StageUIItem(
    val stageInfo: StageInfo,
    val icon: ImageVector,
    val phaseNumber: Int
)

val stageUIList = listOf(
    StageUIItem(StageInfo.IMPORT, Icons.Default.VideoLibrary, 2),
    StageUIItem(StageInfo.AUDIO, Icons.Default.GraphicEq, 3),
    StageUIItem(StageInfo.TRANSCRIPT, Icons.Default.Article, 4),
    StageUIItem(StageInfo.AI_ANALYSIS, Icons.Default.AutoAwesome, 5),
    StageUIItem(StageInfo.CLIP_PLAN, Icons.Default.ContentCut, 6),
    StageUIItem(StageInfo.TRACKING, Icons.Default.CropFree, 7),
    StageUIItem(StageInfo.CAPTIONS, Icons.Default.ClosedCaption, 8),
    StageUIItem(StageInfo.RENDER, Icons.Default.MovieFilter, 9),
    StageUIItem(StageInfo.COMPLETE, Icons.Default.CheckCircle, 10)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    viewModel: ProjectViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val project by viewModel.project.collectAsStateWithLifecycle()
    val selectedStageIndex by viewModel.selectedStageIndex.collectAsStateWithLifecycle()

    // Import States
    val isImporting by viewModel.isImporting.collectAsStateWithLifecycle()
    val importProgress by viewModel.importProgress.collectAsStateWithLifecycle()
    val importError by viewModel.importError.collectAsStateWithLifecycle()
    val urlError by viewModel.urlError.collectAsStateWithLifecycle()
    val urlMessage by viewModel.urlMessage.collectAsStateWithLifecycle()
    val thumbnailBitmap by viewModel.thumbnailBitmap.collectAsStateWithLifecycle()

    // Audio Stage States
    val isExtractingAudio by viewModel.isExtractingAudio.collectAsStateWithLifecycle()
    val extractionProgress by viewModel.extractionProgress.collectAsStateWithLifecycle()
    val extractionStatusText by viewModel.extractionStatusText.collectAsStateWithLifecycle()
    val audioError by viewModel.audioError.collectAsStateWithLifecycle()
    val audioManifest by viewModel.audioManifest.collectAsStateWithLifecycle()

    // Phase 4 Transcription States
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()
    val isWorkerProcessing by viewModel.isWorkerProcessing.collectAsStateWithLifecycle()
    val isWorkerPaused by viewModel.isWorkerPaused.collectAsStateWithLifecycle()
    val transcriptionError by viewModel.transcriptionError.collectAsStateWithLifecycle()
    val authError by viewModel.authError.collectAsStateWithLifecycle()
    val quotaError by viewModel.quotaError.collectAsStateWithLifecycle()

    // Phase 6 AI Virality Analysis & Discovery States
    val clipCandidates by viewModel.clipCandidates.collectAsStateWithLifecycle()
    val isAnalyzingClips by viewModel.isAnalyzingClips.collectAsStateWithLifecycle()
    val analysisProgressText by viewModel.analysisProgressText.collectAsStateWithLifecycle()
    val analysisError by viewModel.analysisError.collectAsStateWithLifecycle()
    val clipsStatusMessage by viewModel.clipsStatusMessage.collectAsStateWithLifecycle()

    // Phase 7 Deterministic Timestamp Verification States
    val verifiedClips by viewModel.verifiedClips.collectAsStateWithLifecycle()
    val isVerifyingTimestamps by viewModel.isVerifyingTimestamps.collectAsStateWithLifecycle()
    val verificationSummary by viewModel.verificationSummary.collectAsStateWithLifecycle()

    // Phase 8 Video Cutting States
    val isCuttingQueue by viewModel.isCuttingQueue.collectAsStateWithLifecycle()
    val activeCuttingClipId by viewModel.activeCuttingClipId.collectAsStateWithLifecycle()
    val activeClipProgress by viewModel.activeClipProgress.collectAsStateWithLifecycle()
    val queueStatusText by viewModel.queueStatusText.collectAsStateWithLifecycle()
    val storageError by viewModel.storageError.collectAsStateWithLifecycle()

    // Phase 9 Tracking & 9:16 States
    val isTrackingProcessing by viewModel.isTrackingProcessing.collectAsStateWithLifecycle()
    val activeTrackingClipId by viewModel.activeTrackingClipId.collectAsStateWithLifecycle()
    val trackingProgress by viewModel.trackingProgress.collectAsStateWithLifecycle()
    val trackingStatusText by viewModel.trackingStatusText.collectAsStateWithLifecycle()

    // Phase 11 Captions States
    val allPresets by viewModel.allPresets.collectAsStateWithLifecycle()
    val activeClipCues by viewModel.activeClipCues.collectAsStateWithLifecycle()
    val activeCaptionClipId by viewModel.activeCaptionClipId.collectAsStateWithLifecycle()

    // Phase 12 Effects & Render States
    val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
    val activeExportClipId by viewModel.activeExportClipId.collectAsStateWithLifecycle()
    val exportProgress by viewModel.exportProgress.collectAsStateWithLifecycle()
    val exportStatusText by viewModel.exportStatusText.collectAsStateWithLifecycle()

    var showActivityLogDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }
    var showTopMenu by remember { mutableStateOf(false) }

    // SAF Video Picker Launcher
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importVideoUri(uri)
        }
    }

    var permissionDeniedMessage by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            permissionDeniedMessage = null
            videoPickerLauncher.launch(arrayOf("video/*"))
        } else {
            permissionDeniedMessage = "Storage permission was denied. Please allow video file access in app settings to select videos."
        }
    }

    fun launchVideoPicker() {
        permissionDeniedMessage = null
        viewModel.clearImportError()

        val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED) {
            videoPickerLauncher.launch(arrayOf("video/*"))
        } else {
            permissionLauncher.launch(requiredPermission)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = project?.name ?: "Clip Project",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showActivityLogDialog = true },
                        modifier = Modifier.testTag("top_activity_log_btn")
                    ) {
                        Icon(imageVector = Icons.Default.History, contentDescription = "Activity Log")
                    }
                    Box {
                        IconButton(
                            onClick = { showTopMenu = true },
                            modifier = Modifier.testTag("top_more_menu_btn")
                        ) {
                            Icon(imageVector = Icons.Default.MoreVert, contentDescription = "More Options")
                        }
                        DropdownMenu(
                            expanded = showTopMenu,
                            onDismissRequest = { showTopMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Duplicate Project") },
                                onClick = {
                                    showTopMenu = false
                                    viewModel.duplicateCurrentProject { newId ->
                                        android.widget.Toast.makeText(context, "Project duplicated successfully!", android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete Project", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showTopMenu = false
                                    showDeleteConfirmDialog = true
                                },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Horizontal Stage Tracker
            Text(
                text = "PROJECT STAGES",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                itemsIndexed(stageUIList) { index, stageItem ->
                    val stageStatus = project?.stages?.get(stageItem.stageInfo.key) ?: "not_started"
                    val isSelected = index == selectedStageIndex
                    val isCompleted = stageStatus == "completed"

                    StageChipItem(
                        stageItem = stageItem,
                        isSelected = isSelected,
                        isCompleted = isCompleted,
                        onClick = { viewModel.selectStage(index) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Active Stage Content
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
            ) {
                when (selectedStageIndex) {
                    0 -> {
                        // Stage 1: Import Stage
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            ImportStageContent(
                                project = project,
                                thumbnailBitmap = thumbnailBitmap,
                                importError = importError,
                                permissionError = permissionDeniedMessage,
                                urlError = urlError,
                                urlMessage = urlMessage,
                                onSelectVideoClick = { launchVideoPicker() },
                                onRemoveVideoClick = { viewModel.removeImportedVideo() },
                                onFetchUrlClick = { url -> viewModel.fetchVideoUrl(url) },
                                onDismissError = { viewModel.clearImportError() }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                    1 -> {
                        // Stage 2: Audio Extraction & Chunking Stage
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            AudioStageContent(
                                project = project,
                                audioManifest = audioManifest,
                                audioError = audioError,
                                onExtractAudioClick = { viewModel.extractAudio() },
                                onRetryChunkClick = { index -> viewModel.retryChunk(index) },
                                onPauseClick = { viewModel.pauseChunkWorker() },
                                onResumeClick = { viewModel.resumeChunkWorker() },
                                onProceedToTranscript = { viewModel.selectStage(2) }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                    2 -> {
                        // Stage 3: Transcript Stage (Deepgram Nova-3 & Phase 5 Viewer)
                        TranscriptStageContent(
                            project = project,
                            audioManifest = audioManifest,
                            transcript = transcript,
                            isWorkerProcessing = isWorkerProcessing,
                            isWorkerPaused = isWorkerPaused,
                            transcriptionError = transcriptionError,
                            authError = authError,
                            quotaError = quotaError,
                            hasDeepgramKey = viewModel.hasDeepgramApiKey(),
                            hasGeminiKey = viewModel.hasGeminiApiKey(),
                            onStartTranscription = { fallback -> viewModel.startTranscription(fallback) },
                            onRetryChunkClick = { index -> viewModel.retryChunk(index) },
                            onPauseClick = { viewModel.pauseChunkWorker() },
                            onResumeClick = { viewModel.resumeChunkWorker() },
                            onDismissError = { viewModel.clearTranscriptionError() },
                            onNavigateToSettings = onNavigateToSettings,
                            onGoToAudioStage = { viewModel.selectStage(1) },
                            onProceedToAiAnalysis = { viewModel.selectStage(3) },
                            onUpdateSegmentText = { segmentId, newText -> viewModel.updateSegmentText(segmentId, newText) },
                            onRenameSpeakerGlobally = { oldLabel, newLabel -> viewModel.renameSpeakerGlobally(oldLabel, newLabel) },
                            onExportSrt = { onSuccess, onError -> viewModel.exportSrt(onSuccess, onError) }
                        )
                    }
                    3 -> {
                        // Stage 4: AI Analysis Stage (Gemini Flash Virality Discovery & Scoring)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            AiAnalysisStageContent(
                                project = project,
                                transcript = transcript,
                                clipCandidates = clipCandidates,
                                isAnalyzing = isAnalyzingClips,
                                progressText = analysisProgressText,
                                analysisError = analysisError,
                                clipsStatusMessage = clipsStatusMessage,
                                hasGeminiKey = viewModel.hasGeminiApiKey(),
                                onFindClips = { count, minLen -> viewModel.findClips(count, minLen) },
                                onToggleExport = { clipId, isIncluded -> viewModel.toggleClipExport(clipId, isIncluded) },
                                onDismissError = { viewModel.clearAnalysisError() },
                                onNavigateToSettings = onNavigateToSettings,
                                onGoToTranscriptStage = { viewModel.selectStage(2) },
                                onProceedToClipPlan = { viewModel.selectStage(4) }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                    4 -> {
                        // Stage 5: Clip Plan Stage (Phase 7 Deterministic Timestamp Verification)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            ClipPlanStageContent(
                                project = project,
                                verifiedClips = verifiedClips,
                                isVerifying = isVerifyingTimestamps,
                                verificationSummary = verificationSummary,
                                onReVerify = { viewModel.verifyTimestamps() },
                                onUseFoundTime = { clipId -> viewModel.useFoundTime(clipId) },
                                onManualEdit = { clipId, sMs, eMs, hsMs, heMs ->
                                    viewModel.updateClipTimesManually(clipId, sMs, eMs, hsMs, heMs)
                                },
                                onDiscardClip = { clipId -> viewModel.discardClip(clipId) },
                                onToggleExport = { clipId, isInc -> viewModel.toggleVerifiedClipExport(clipId, isInc) },
                                onGoToAiAnalysis = { viewModel.selectStage(3) },
                                onProceedToTracking = { viewModel.selectStage(5) },
                                onCutClip = { clipId -> viewModel.cutClip(clipId) },
                                onCutAllVerified = { viewModel.cutAllVerifiedClips() },
                                onDeleteDraft = { clipId -> viewModel.deleteDraft(clipId) },
                                onAdjustClipTimes = { clipId, sMs, eMs, hsMs, heMs, timesChanged ->
                                    viewModel.adjustClipTimes(clipId, sMs, eMs, hsMs, heMs, timesChanged)
                                },
                                isCuttingQueue = isCuttingQueue,
                                activeCuttingClipId = activeCuttingClipId,
                                activeClipProgress = activeClipProgress,
                                queueStatusText = queueStatusText,
                                storageError = storageError,
                                onDismissStorageError = { viewModel.clearStorageError() }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                    5 -> {
                        // Stage 6: Tracking Stage (Phase 9 ML Kit Face Tracking & 9:16 Vertical Reframing)
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            TrackingStageContent(
                                project = project,
                                verifiedClips = verifiedClips,
                                onCutClip = { clipId -> viewModel.cutClip(clipId) },
                                onCutAllVerified = { viewModel.cutAllVerifiedClips() },
                                onDeleteDraft = { clipId -> viewModel.deleteDraft(clipId) },
                                onAdjustClipTimes = { clipId, sMs, eMs, hsMs, heMs, timesChanged ->
                                    viewModel.adjustClipTimes(clipId, sMs, eMs, hsMs, heMs, timesChanged)
                                },
                                isCuttingQueue = isCuttingQueue,
                                activeCuttingClipId = activeCuttingClipId,
                                activeClipProgress = activeClipProgress,
                                queueStatusText = queueStatusText,
                                storageError = storageError,
                                onDismissStorageError = { viewModel.clearStorageError() },
                                onGoToClipPlan = { viewModel.selectStage(4) },
                                onAnalyzeFaceTrack = { clipId, force -> viewModel.analyzeFaceTrack(clipId, force) },
                                onRenderVertical = { clipId -> viewModel.renderVerticalDraft(clipId) },
                                onUpdateManualCrop = { clipId, offX, margin -> viewModel.updateManualCrop(clipId, offX, margin) },
                                onUpdateLayoutSelection = { clipId, lType -> viewModel.updateLayoutSelection(clipId, lType) },
                                onUpdateSelectedPersons = { clipId, sIds -> viewModel.updateSelectedPersons(clipId, sIds) },
                                onApproveLayoutReview = { clipId -> viewModel.approveLayoutReview(clipId) },
                                getLayoutPlan = { clip -> viewModel.getLayoutPlan(clip) },
                                getCropPlan = { clip -> viewModel.getCropPlan(clip) },
                                isTrackingProcessing = isTrackingProcessing,
                                activeTrackingClipId = activeTrackingClipId,
                                trackingProgress = trackingProgress,
                                trackingStatusText = trackingStatusText,
                                onProceedToCaptions = { viewModel.selectStage(6) }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                    6 -> {
                        // Stage 7: Captions Stage
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                        ) {
                            CaptionsStageContent(
                                project = project,
                                verifiedClips = verifiedClips,
                                allPresets = allPresets,
                                activeClipCues = activeClipCues,
                                activeCaptionClipId = activeCaptionClipId,
                                onSelectActiveClip = { clipId -> viewModel.selectActiveCaptionClip(clipId) },
                                onUpdateCueText = { clipId, cueId, text -> viewModel.updateCaptionCueText(clipId, cueId, text) },
                                onSplitCue = { clipId, cueId, wordIdx -> viewModel.splitCaptionCue(clipId, cueId, wordIdx) },
                                onMergeCue = { clipId, cueId -> viewModel.mergeCaptionCue(clipId, cueId) },
                                onSelectPreset = { clipId, presetId -> viewModel.selectPresetForClip(clipId, presetId) },
                                onToggleBurnIn = { clipId, burn -> viewModel.toggleBurnInForClip(clipId, burn) },
                                onImportPreset = { json, onLogs -> viewModel.importCustomPreset(json, onLogs) },
                                onExportPreset = { p, onPath -> viewModel.exportPreset(p, onPath) },
                                onDeletePreset = { id -> viewModel.deleteCustomPreset(id) },
                                onMarkStageComplete = { viewModel.markCaptionsStageComplete() },
                                onProceedToRender = { viewModel.selectStage(7) }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                    7, 8 -> {
                        // Stage 8: Render & Stage 9: Complete
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                        ) {
                            RenderStageContent(
                                project = project,
                                verifiedClips = verifiedClips,
                                isExporting = isExporting,
                                activeExportClipId = activeExportClipId,
                                exportProgress = exportProgress,
                                exportStatusText = exportStatusText,
                                onExportAll = { resumeOnly -> viewModel.exportAllClips(resumeOnly) },
                                onRenderClip = { clipId -> viewModel.renderSingleClip(clipId) },
                                onUpdateFilter = { clipId, filter -> viewModel.updateClipFilter(clipId, filter) },
                                onToggleMirror = { clipId, mirrored -> viewModel.toggleClipMirror(clipId, mirrored) },
                                onUpdateReframe = { clipId, offX, zoom -> viewModel.updateClipReframe(clipId, offX, zoom) },
                                onOpenActivityLog = { showActivityLogDialog = true }
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                    else -> {
                        // Placeholders
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            val activeStage = stageUIList.getOrNull(selectedStageIndex) ?: stageUIList.first()
                            PlaceholderStageContent(stageItem = activeStage)
                            Spacer(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }

    // --- ACTIVITY LOG DIALOG ---
    if (showActivityLogDialog) {
        ActivityLogDialog(
            logs = project?.activityLogs ?: emptyList(),
            onDismiss = { showActivityLogDialog = false }
        )
    }

    // --- DELETE CONFIRMATION DIALOG ---
    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Delete Project?") },
            text = { Text("This will permanently delete this project and all associated video clips and drafts from your device.") },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteCurrentProject {
                            onNavigateBack()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete Permanently")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Progress Dialog during video copy
    if (isImporting) {
        val percentage = (importProgress * 100).toInt()
        ClipGeniusProgressDialog(
            show = true,
            title = "Importing Video...",
            message = "Copying selected video into app-private storage ($percentage%)",
            progress = importProgress,
            onDismissRequest = {}
        )
    }

    // Progress Dialog during audio extraction
    if (isExtractingAudio) {
        ClipGeniusProgressDialog(
            show = true,
            title = "Extracting Audio...",
            message = extractionStatusText,
            progress = extractionProgress,
            onDismissRequest = {}
        )
    }
}

@Composable
private fun AudioStageContent(
    project: com.clipgenius.ai.state.Project?,
    audioManifest: AudioChunkManifest?,
    audioError: String?,
    onExtractAudioClick: () -> Unit,
    onRetryChunkClick: (Int) -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
    onProceedToTranscript: () -> Unit
) {
    val media = project?.sourceMedia

    Column(modifier = Modifier.fillMaxWidth()) {

        // Error Banner
        audioError?.let { err ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .testTag("audio_error_banner")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (media == null) {
            // No video imported yet
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
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Video Imported Yet",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Please go to the Import stage first and select a video file before extracting audio.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else if (audioManifest == null) {
            // Audio Extraction Action Card
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Audio Extraction & Chunking",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Source: ${media.fileName}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (!media.hasAudio) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeOff,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "This video has no audio track, so transcription cannot proceed.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Extracts the audio track into an M4A file without re-encoding when possible. Videos over 30 minutes are automatically split into 20-minute chunks with 60s overlap for AI transcription.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = onExtractAudioClick,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("extract_audio_button")
                        ) {
                            Icon(imageVector = Icons.Default.GraphicEq, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Extract Audio Track",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        } else {
            // Audio Chunks & Worker Progress UI
            val chunks = audioManifest.chunks
            val totalChunks = chunks.size
            val doneChunks = chunks.count { it.status == ChunkStatus.DONE }
            val overallProgress = if (totalChunks > 0) doneChunks.toFloat() / totalChunks.toFloat() else 0f
            val isAllDone = totalChunks > 0 && doneChunks == totalChunks

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
                    // Header & Progress Summary
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Audio Chunks ($doneChunks of $totalChunks Prepared)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Parallel Queue (Max 3 Concurrent Workers)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (isAllDone) {
                            Surface(
                                color = StageCompleted.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = StageCompleted,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "Complete",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = StageCompleted
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Progress Bar
                    LinearProgressIndicator(
                        progress = { overallProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = if (isAllDone) StageCompleted else MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Per-Chunk Rows
                    chunks.forEach { chunk ->
                        ChunkRowItem(
                            chunk = chunk,
                            onRetryClick = { onRetryChunkClick(chunk.index) }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (isAllDone) {
                        Surface(
                            color = StageCompleted.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "All Audio Chunks Validated & Ready!",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StageCompleted
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Audio track is prepared and chunked for Phase 4 (Deepgram AI Transcription).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Button(
                                    onClick = onProceedToTranscript,
                                    colors = ButtonDefaults.buttonColors(containerColor = StageCompleted),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("proceed_to_transcript_button")
                                ) {
                                    Text("Proceed to Transcript Stage", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    } else {
                        // Re-extract Button
                        OutlinedButton(
                            onClick = onExtractAudioClick,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Re-extract Audio", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChunkRowItem(
    chunk: AudioChunk,
    onRetryClick: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("chunk_row_${chunk.index}")
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Status Icon
                when (chunk.status) {
                    ChunkStatus.QUEUED -> Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Queued",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    ChunkStatus.PROCESSING -> CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    ChunkStatus.DONE -> Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Done",
                        tint = StageCompleted,
                        modifier = Modifier.size(20.dp)
                    )
                    ChunkStatus.FAILED -> Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = "Failed",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "Chunk #${chunk.index + 1}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${AudioExtractor.formatChunkTimeRange(chunk.startMs, chunk.endMs)} ${if (chunk.overlapMs > 0) "• 60s overlap" else ""}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Action / Status Text
            if (chunk.status == ChunkStatus.FAILED) {
                Button(
                    onClick = onRetryClick,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.testTag("retry_chunk_button_${chunk.index}")
                ) {
                    Text("Retry", style = MaterialTheme.typography.labelSmall)
                }
            } else {
                Text(
                    text = when (chunk.status) {
                        ChunkStatus.QUEUED -> "Queued"
                        ChunkStatus.PROCESSING -> "Processing..."
                        ChunkStatus.DONE -> "Ready"
                        ChunkStatus.FAILED -> "Failed"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = when (chunk.status) {
                        ChunkStatus.DONE -> StageCompleted
                        ChunkStatus.PROCESSING -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }
    }
}

@Composable
private fun ImportStageContent(
    project: com.clipgenius.ai.state.Project?,
    thumbnailBitmap: android.graphics.Bitmap?,
    importError: String?,
    permissionError: String?,
    urlError: String?,
    urlMessage: String?,
    onSelectVideoClick: () -> Unit,
    onRemoveVideoClick: () -> Unit,
    onFetchUrlClick: (String) -> Unit,
    onDismissError: () -> Unit
) {
    val hasVideo = !project?.sourceVideoPath.isNullOrBlank() && project?.sourceMedia != null

    var videoUrlInput by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth()) {

        (importError ?: permissionError)?.let { err ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .testTag("import_error_banner")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (hasVideo) {
            val media = project!!.sourceMedia!!

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("video_metadata_card")
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.VideoFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Source Video File",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Surface(
                            color = StageCompleted.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = StageCompleted,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Import Complete",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = StageCompleted
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (thumbnailBitmap != null) {
                            Image(
                                bitmap = thumbnailBitmap.asImageBitmap(),
                                contentDescription = "Video Thumbnail",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.VideoLibrary,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = media.fileName.ifBlank { "original.mp4" },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetadataBadge(
                            label = "Duration",
                            value = VideoInputHandler.formatDuration(media.durationMs)
                        )
                        MetadataBadge(
                            label = "Resolution",
                            value = VideoInputHandler.formatResolution(media.width, media.height, media.rotation)
                        )
                        MetadataBadge(
                            label = "Frame Rate",
                            value = "${media.frameRate.toInt()} fps"
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetadataBadge(
                            label = "File Size",
                            value = VideoInputHandler.formatFileSize(media.fileSizeBytes)
                        )
                        MetadataBadge(
                            label = "Codec",
                            value = media.codec.substringAfterLast('/')
                        )
                        AudioBadge(hasAudio = media.hasAudio)
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Private Storage Copy: ${media.path}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.padding(8.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    OutlinedButton(
                        onClick = onRemoveVideoClick,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("remove_video_button")
                    ) {
                        Icon(imageVector = Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Remove / Pick Another Video",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

        } else {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("video_picker_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Import Source Video",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Select a video from your device storage. The original file is never touched or modified.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = onSelectVideoClick,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("select_video_button")
                    ) {
                        Icon(imageVector = Icons.Default.VideoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Select Video",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("video_link_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Link,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Or paste a YouTube/video link",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = videoUrlInput,
                            onValueChange = { videoUrlInput = it },
                            placeholder = { Text("https://youtube.com/watch?v=...") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("video_link_input")
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = { onFetchUrlClick(videoUrlInput) },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(52.dp)
                                .testTag("fetch_link_button")
                        ) {
                            Text("Fetch", fontWeight = FontWeight.Bold)
                        }
                    }

                    urlError?.let { err ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("url_error_text")
                        )
                    }

                    urlMessage?.let { msg ->
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("honest_link_message_banner")
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = msg,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataBadge(label: String, value: String) {
    Column(
        modifier = Modifier.padding(4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun AudioBadge(hasAudio: Boolean) {
    Column(
        modifier = Modifier.padding(4.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "Audio Track",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (hasAudio) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                contentDescription = null,
                tint = if (hasAudio) StageCompleted else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = if (hasAudio) "Yes" else "No",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (hasAudio) StageCompleted else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun StageChipItem(
    stageItem: StageUIItem,
    isSelected: Boolean,
    isCompleted: Boolean,
    onClick: () -> Unit
) {
    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primary
        isCompleted -> StageCompleted.copy(alpha = 0.2f)
        else -> StageInactive
    }

    val contentColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isCompleted -> StageCompleted
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = backgroundColor,
        modifier = Modifier
            .border(2.dp, borderColor, RoundedCornerShape(20.dp))
            .clickable { onClick() }
            .testTag("stage_chip_${stageItem.stageInfo.displayName.lowercase()}")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isCompleted && !isSelected) Icons.Default.CheckCircle else stageItem.icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = stageItem.stageInfo.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = contentColor
            )
        }
    }
}

@Composable
private fun PlaceholderStageContent(stageItem: StageUIItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("stage_content_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = stageItem.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Stage: ${stageItem.stageInfo.displayName}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stageItem.stageInfo.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Coming in Phase ${stageItem.phaseNumber}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Audio Stage (Phase 3) is active. In Phase ${stageItem.phaseNumber}, full processing logic for ${stageItem.stageInfo.displayName} will be implemented.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

/**
 * Placeholder for Stage 6 (Tracking) verifying Phase 7 Deterministic Timestamp Verification completion.
 */
@Composable
fun TrackingStagePlaceholder(
    verifiedClips: List<com.clipgenius.ai.state.VerifiedClip>,
    onGoToClipPlan: () -> Unit
) {
    val exportableClips = verifiedClips.filter { it.status == com.clipgenius.ai.state.VerificationStatus.VERIFIED && it.isIncludedInExport }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .testTag("tracking_stage_placeholder_card")
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = "Tracking Ready",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Stage 6: Tracking & Vertical Framing",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Phase 8 Placeholder • Timestamps Verified Against Transcript",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (exportableClips.isNotEmpty()) {
                Text(
                    text = "${exportableClips.size} clips have passed deterministic timestamp & quote verification. In Phase 8, automated speaker face tracking, active camera pan, and 9:16 vertical cropping will be rendered for each clip.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "VERIFIED CLIPS READY FOR TRACKING (${exportableClips.size})",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        exportableClips.take(5).forEachIndexed { i, c ->
                            val r = c.verifiedRanges.firstOrNull()
                            val timingStr = if (r != null) "${com.clipgenius.ai.verification.TimeUtils.formatMs(r.startMs)} - ${com.clipgenius.ai.verification.TimeUtils.formatMs(r.endMs)}" else ""
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${i + 1}. ${c.originalCandidate.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = timingStr,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "No clips are currently verified. Open Stage 5 (Clip Plan) to verify AI-discovered timestamps or correct invalid quotes.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onGoToClipPlan,
                modifier = Modifier.testTag("back_to_clip_plan_button")
            ) {
                Text("← Back to Clip Plan (Stage 5)")
            }
        }
    }
}

