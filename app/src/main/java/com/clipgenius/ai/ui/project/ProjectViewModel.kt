package com.clipgenius.ai.ui.project

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.clipgenius.ai.aiplanner.AiClipPlanner
import com.clipgenius.ai.aiplanner.GeminiClient
import com.clipgenius.ai.audio.AudioExtractor
import com.clipgenius.ai.audio.ChunkWorkerManager
import com.clipgenius.ai.data.ProjectRepository
import com.clipgenius.ai.input.VideoInputHandler
import com.clipgenius.ai.state.AudioChunkManifest
import com.clipgenius.ai.state.ChunkStatus
import com.clipgenius.ai.state.ClipCandidate
import com.clipgenius.ai.state.Project
import com.clipgenius.ai.state.ProjectTranscript
import com.clipgenius.ai.state.TranscriptSegment
import com.clipgenius.ai.transcription.DeepgramClient
import com.clipgenius.ai.transcription.GeminiFallbackTranscriber
import com.clipgenius.ai.transcription.TranscriptMerger
import com.clipgenius.ai.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ProjectViewModel(
    private val projectId: String,
    private val repository: ProjectRepository,
    private val context: Context
) : ViewModel() {

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _selectedStageIndex = MutableStateFlow(0)
    val selectedStageIndex: StateFlow<Int> = _selectedStageIndex.asStateFlow()

    // Phase 2 Import States
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importProgress = MutableStateFlow(0f)
    val importProgress: StateFlow<Float> = _importProgress.asStateFlow()

    private val _importError = MutableStateFlow<String?>(null)
    val importError: StateFlow<String?> = _importError.asStateFlow()

    private val _urlError = MutableStateFlow<String?>(null)
    val urlError: StateFlow<String?> = _urlError.asStateFlow()

    private val _urlMessage = MutableStateFlow<String?>(null)
    val urlMessage: StateFlow<String?> = _urlMessage.asStateFlow()

    private val _thumbnailBitmap = MutableStateFlow<Bitmap?>(null)
    val thumbnailBitmap: StateFlow<Bitmap?> = _thumbnailBitmap.asStateFlow()

    // Phase 3 Audio Extraction & Chunking States
    private val _isExtractingAudio = MutableStateFlow(false)
    val isExtractingAudio: StateFlow<Boolean> = _isExtractingAudio.asStateFlow()

    private val _extractionProgress = MutableStateFlow(0f)
    val extractionProgress: StateFlow<Float> = _extractionProgress.asStateFlow()

    private val _extractionStatusText = MutableStateFlow("Extracting audio...")
    val extractionStatusText: StateFlow<String> = _extractionStatusText.asStateFlow()

    private val _audioError = MutableStateFlow<String?>(null)
    val audioError: StateFlow<String?> = _audioError.asStateFlow()

    private val _audioManifest = MutableStateFlow<AudioChunkManifest?>(null)
    val audioManifest: StateFlow<AudioChunkManifest?> = _audioManifest.asStateFlow()

    // Phase 4 Transcription States
    private val deepgramClient = DeepgramClient(context)
    private val geminiTranscriber = GeminiFallbackTranscriber(context)

    private val _transcript = MutableStateFlow<ProjectTranscript?>(null)
    val transcript: StateFlow<ProjectTranscript?> = _transcript.asStateFlow()

    private val _transcriptionError = MutableStateFlow<String?>(null)
    val transcriptionError: StateFlow<String?> = _transcriptionError.asStateFlow()

    val chunkWorkerManager = ChunkWorkerManager(
        context = context,
        projectId = projectId,
        onManifestUpdated = { updatedManifest ->
            onManifestUpdated(updatedManifest)
        },
        onAllChunksTranscribed = { chunkSegmentsMap, isFallback ->
            onAllChunksCompleted(chunkSegmentsMap, isFallback)
        }
    )

    val isWorkerProcessing: StateFlow<Boolean> = chunkWorkerManager.isProcessing
    val isWorkerPaused: StateFlow<Boolean> = chunkWorkerManager.isPaused
    val authError: StateFlow<String?> = chunkWorkerManager.authError
    val quotaError: StateFlow<String?> = chunkWorkerManager.quotaError

    // Phase 6 AI Virality Analysis & Clip Planning States
    private val aiClipPlanner = AiClipPlanner(context)
    private val geminiClient = GeminiClient(context)

    private val _clipCandidates = MutableStateFlow<List<ClipCandidate>>(emptyList())
    val clipCandidates: StateFlow<List<ClipCandidate>> = _clipCandidates.asStateFlow()

    private val _isAnalyzingClips = MutableStateFlow(false)
    val isAnalyzingClips: StateFlow<Boolean> = _isAnalyzingClips.asStateFlow()

    private val _analysisProgressText = MutableStateFlow("")
    val analysisProgressText: StateFlow<String> = _analysisProgressText.asStateFlow()

    private val _analysisError = MutableStateFlow<String?>(null)
    val analysisError: StateFlow<String?> = _analysisError.asStateFlow()

    private val _clipsStatusMessage = MutableStateFlow<String?>(null)
    val clipsStatusMessage: StateFlow<String?> = _clipsStatusMessage.asStateFlow()

    // Phase 7 Timestamp Verification States
    private val timestampVerifier = com.clipgenius.ai.verification.TimestampVerifier()
    private val _verifiedClips = MutableStateFlow<List<com.clipgenius.ai.state.VerifiedClip>>(emptyList())
    val verifiedClips: StateFlow<List<com.clipgenius.ai.state.VerifiedClip>> = _verifiedClips.asStateFlow()

    private val _isVerifyingTimestamps = MutableStateFlow(false)
    val isVerifyingTimestamps: StateFlow<Boolean> = _isVerifyingTimestamps.asStateFlow()

    private val _verificationSummary = MutableStateFlow<String?>(null)
    val verificationSummary: StateFlow<String?> = _verificationSummary.asStateFlow()

    // Phase 8 Cutting States
    val isCuttingQueue: StateFlow<Boolean> = com.clipgenius.ai.cutter.ClipCutterManager.isCuttingQueue
    val activeCuttingClipId: StateFlow<String?> = com.clipgenius.ai.cutter.ClipCutterManager.activeCuttingClipId
    val activeClipProgress: StateFlow<Float> = com.clipgenius.ai.cutter.ClipCutterManager.activeClipProgress
    val queueStatusText: StateFlow<String?> = com.clipgenius.ai.cutter.ClipCutterManager.queueStatusText
    val storageError: StateFlow<String?> = com.clipgenius.ai.cutter.ClipCutterManager.storageError

    // Phase 9 Tracking & 9:16 Reframing States
    val isTrackingProcessing: StateFlow<Boolean> = com.clipgenius.ai.tracking.TrackingManager.isProcessing
    val activeTrackingClipId: StateFlow<String?> = com.clipgenius.ai.tracking.TrackingManager.activeClipId
    val trackingProgress: StateFlow<Float> = com.clipgenius.ai.tracking.TrackingManager.operationProgress
    val trackingStatusText: StateFlow<String?> = com.clipgenius.ai.tracking.TrackingManager.operationStatusText

    // Phase 11 Caption & Preset States
    private val _allPresets = MutableStateFlow<List<com.clipgenius.ai.state.CaptionPreset>>(emptyList())
    val allPresets: StateFlow<List<com.clipgenius.ai.state.CaptionPreset>> = _allPresets.asStateFlow()

    private val _activeClipCues = MutableStateFlow<List<com.clipgenius.ai.state.CaptionCue>>(emptyList())
    val activeClipCues: StateFlow<List<com.clipgenius.ai.state.CaptionCue>> = _activeClipCues.asStateFlow()

    private val _activeCaptionClipId = MutableStateFlow<String?>(null)
    val activeCaptionClipId: StateFlow<String?> = _activeCaptionClipId.asStateFlow()

    // Phase 12 Effects & Final Export States
    val isExporting: StateFlow<Boolean> = com.clipgenius.ai.render.FinalExporter.isExporting
    val activeExportClipId: StateFlow<String?> = com.clipgenius.ai.render.FinalExporter.activeClipId
    val exportProgress: StateFlow<Float> = com.clipgenius.ai.render.FinalExporter.exportProgress
    val exportStatusText: StateFlow<String?> = com.clipgenius.ai.render.FinalExporter.exportStatusText

    init {
        loadProject()
        observeProjectUpdates()
        observePresets()
    }

    private fun observePresets() {
        viewModelScope.launch {
            repository.allPresets.collect { presets ->
                if (presets.isEmpty()) {
                    com.clipgenius.ai.captions.CaptionManager.seedBuiltInPresets(repository)
                } else {
                    _allPresets.value = presets
                }
            }
        }
    }

    private fun observeProjectUpdates() {
        viewModelScope.launch {
            repository.allProjects.collect { projects ->
                val current = projects.find { it.id == projectId }
                if (current != null) {
                    _project.value = current
                    _verifiedClips.value = current.verifiedClips
                    updateVerificationSummary(current.verifiedClips)
                }
            }
        }
    }

    private fun loadProject() {
        viewModelScope.launch {
            val p = repository.getProjectById(projectId)
            _project.value = p
            _audioManifest.value = p?.audioManifest
            _transcript.value = p?.transcript
            _clipCandidates.value = p?.clipCandidates ?: emptyList()
            _verifiedClips.value = p?.verifiedClips ?: emptyList()
            updateVerificationSummary(_verifiedClips.value)

            // If transcript not yet in Room object, check if disk cache exists
            if (p?.transcript == null) {
                checkAndLoadDiskTranscript()
            }

            // If clip candidates not in Room, check disk cache
            if (_clipCandidates.value.isEmpty()) {
                checkAndLoadDiskClips()
            }

            // If clips exist but verification has not run yet, auto-verify
            if (_verifiedClips.value.isEmpty() && _clipCandidates.value.isNotEmpty()) {
                verifyTimestamps(_clipCandidates.value)
            }

            // Load thumbnail if video exists
            p?.sourceVideoPath?.let { path ->
                if (path.isNotBlank()) {
                    val file = File(path)
                    if (file.exists()) {
                        withContext(Dispatchers.IO) {
                            val thumb = VideoInputHandler.extractThumbnail(file)
                            _thumbnailBitmap.value = thumb
                        }
                    }
                }
            }
        }
    }

    private suspend fun checkAndLoadDiskClips() {
        withContext(Dispatchers.IO) {
            val diskClips = aiClipPlanner.loadClipsFromDisk(projectId)
            if (diskClips.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    _clipCandidates.value = diskClips
                    _clipsStatusMessage.value = "${diskClips.size} clips loaded from cache"
                }
                val currentProject = _project.value
                if (currentProject != null) {
                    val updatedStages = currentProject.stages.toMutableMap().apply {
                        put("AI Analysis", "completed")
                        put("Clip Plan", "in_progress")
                    }
                    val updatedProject = currentProject.copy(
                        clipCandidates = diskClips,
                        stages = updatedStages
                    )
                    repository.saveProject(updatedProject)
                    withContext(Dispatchers.Main) {
                        _project.value = updatedProject
                    }
                }
            }
        }
    }

    private suspend fun checkAndLoadDiskTranscript() {
        withContext(Dispatchers.IO) {
            val transcriptDir = File(context.filesDir, "projects/$projectId/transcript")
            val srtFile = File(transcriptDir, "transcript.srt")
            val jsonFile = File(transcriptDir, "transcript.json")
            if (jsonFile.exists() && srtFile.exists()) {
                val converters = com.clipgenius.ai.data.ProjectTypeConverters()
                val loadedTranscript = converters.toProjectTranscript(jsonFile.readText())
                if (loadedTranscript != null) {
                    _transcript.value = loadedTranscript
                    val currentProject = _project.value
                    if (currentProject != null) {
                        val updatedStages = currentProject.stages.toMutableMap().apply {
                            put("Transcript", "completed")
                            put("AI Analysis", "in_progress")
                        }
                        val updatedProject = currentProject.copy(
                            transcript = loadedTranscript,
                            stages = updatedStages
                        )
                        repository.saveProject(updatedProject)
                        _project.value = updatedProject
                    }
                }
            }
        }
    }

    fun selectStage(index: Int) {
        if (index in 0..8) {
            _selectedStageIndex.value = index
        }
    }

    fun hasDeepgramApiKey(): Boolean {
        return deepgramClient.hasApiKey()
    }

    fun hasGeminiApiKey(): Boolean {
        return geminiTranscriber.hasApiKey()
    }

    // --- PHASE 2 ACTIONS ---
    fun importVideoUri(uri: Uri) {
        CrashLogger.addBreadcrumb("User started video import: uri=$uri")
        viewModelScope.launch {
            _isImporting.value = true
            _importError.value = null
            _importProgress.value = 0f

            try {
                val currentProject = _project.value ?: return@launch

                withContext(Dispatchers.IO) {
                    val originalFileName = VideoInputHandler.getFileNameFromUri(context, uri)

                    val copiedFile = VideoInputHandler.copyVideoToPrivateStorage(
                        context = context,
                        uri = uri,
                        projectId = projectId,
                        onProgress = { progress ->
                            _importProgress.value = progress
                        }
                    )

                    val sourceMedia = VideoInputHandler.inspectMediaFile(context, copiedFile, originalFileName)
                    val thumbnail = VideoInputHandler.extractThumbnail(copiedFile)

                    val updatedName = if (currentProject.name.isBlank() || currentProject.name.startsWith("New Project")) {
                        originalFileName.substringBeforeLast('.')
                    } else {
                        currentProject.name
                    }

                    val updatedStages = currentProject.stages.toMutableMap().apply {
                        put("Import", "completed")
                        put("Audio", "in_progress")
                    }

                    val updatedProject = currentProject.copy(
                        name = updatedName,
                        sourceVideoPath = copiedFile.absolutePath,
                        sourceMedia = sourceMedia,
                        audioManifest = null,
                        transcript = null,
                        stages = updatedStages,
                        errorLog = null
                    )

                    repository.saveProject(updatedProject)
                    _project.value = updatedProject
                    _thumbnailBitmap.value = thumbnail
                    _audioManifest.value = null
                    _transcript.value = null

                    CrashLogger.addBreadcrumb("import ok: file=${copiedFile.name}, size=${copiedFile.length()} bytes, hasAudio=${sourceMedia.hasAudio}")
                }
            } catch (t: Throwable) {
                CrashLogger.logError(context, "ProjectViewModel", "Import failed: ${t.message}", t)
                _importError.value = t.localizedMessage
                    ?: "Could not read this video file. It may be corrupt or in an unsupported format."
            } finally {
                _isImporting.value = false
                _importProgress.value = 0f
            }
        }
    }

    fun removeImportedVideo() {
        viewModelScope.launch {
            val currentProject = _project.value ?: return@launch
            withContext(Dispatchers.IO) {
                VideoInputHandler.deletePrivateCopy(context, projectId)

                val updatedStages = currentProject.stages.toMutableMap().apply {
                    put("Import", "in_progress")
                    put("Audio", "not_started")
                    put("Transcript", "not_started")
                }

                val updatedProject = currentProject.copy(
                    sourceVideoPath = "",
                    sourceMedia = null,
                    audioManifest = null,
                    transcript = null,
                    stages = updatedStages
                )

                repository.saveProject(updatedProject)
                _project.value = updatedProject
                _thumbnailBitmap.value = null
                _audioManifest.value = null
                _transcript.value = null
            }
        }
    }

    fun fetchVideoUrl(url: String) {
        _urlError.value = null
        _urlMessage.value = null

        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank() || !(trimmedUrl.startsWith("http://", ignoreCase = true) || trimmedUrl.startsWith("https://", ignoreCase = true))) {
            _urlError.value = "Please enter a valid video link starting with http:// or https://"
            return
        }

        _urlMessage.value = "Direct download from this link is not supported on this device yet. Please download the video first and import the file above."
    }

    fun clearImportError() {
        _importError.value = null
    }

    // --- PHASE 3 AUDIO EXTRACTION & CHUNKING ACTIONS ---
    fun extractAudio() {
        CrashLogger.addBreadcrumb("User tapped Extract Audio for project $projectId")
        viewModelScope.launch {
            val currentProject = _project.value
            if (currentProject == null) {
                _audioError.value = "Project not found. Please re-open the project."
                return@launch
            }

            // Resolve or inspect media if sourceMedia is missing but video path exists
            val media = currentProject.sourceMedia ?: run {
                val candidatePath = currentProject.sourceVideoPath.ifBlank { currentProject.videoUri }
                if (candidatePath.isNotBlank()) {
                    try {
                        val file = File(candidatePath)
                        if (file.exists()) {
                            VideoInputHandler.inspectMediaFile(context, file)
                        } else null
                    } catch (t: Throwable) {
                        null
                    }
                } else null
            }

            if (media == null) {
                _audioError.value = "No video imported. Please import a video first."
                return@launch
            }

            if (!media.hasAudio) {
                _audioError.value = "This video has no audio track, so transcription cannot proceed."
                CrashLogger.addBreadcrumb("Extract Audio skipped: video has no audio track")
                return@launch
            }

            _isExtractingAudio.value = true
            _audioError.value = null
            _extractionProgress.value = 0f

            try {
                withContext(Dispatchers.IO) {
                    val manifest = AudioExtractor.extractAndChunkAudio(
                        context = context,
                        projectId = projectId,
                        sourceMedia = media,
                        onProgress = { progress, status ->
                            _extractionProgress.value = progress
                            _extractionStatusText.value = status
                        }
                    )

                    _audioManifest.value = manifest
                    val updatedStages = currentProject.stages.toMutableMap().apply {
                        put("Audio", "completed")
                        put("Transcript", "in_progress")
                    }
                    val updatedProject = currentProject.copy(
                        sourceMedia = media,
                        audioManifest = manifest,
                        stages = updatedStages
                    )
                    repository.saveProject(updatedProject)
                    _project.value = updatedProject
                }
            } catch (t: Throwable) {
                CrashLogger.logError(context, "ProjectViewModel", "Audio extraction error: ${t.message}", t)
                _audioError.value = when {
                    t is IllegalArgumentException && t.message?.contains("no audio track", ignoreCase = true) == true ->
                        "This video has no audio track, so transcription cannot proceed."
                    t.message?.contains("no audio track", ignoreCase = true) == true ->
                        "This video has no audio track, so transcription cannot proceed."
                    t is SecurityException || t.message?.contains("permission", ignoreCase = true) == true ->
                        "Storage permission was denied. Please allow video access in app settings."
                    t.message?.contains("storage space", ignoreCase = true) == true ->
                        t.localizedMessage ?: "Not enough free storage space."
                    else ->
                        t.localizedMessage ?: "Audio extraction failed. Your video file is safe. Tap Retry."
                }
            } finally {
                _isExtractingAudio.value = false
                _extractionProgress.value = 0f
            }
        }
    }

    fun clearAudioError() {
        _audioError.value = null
    }

    // --- PHASE 4 TRANSCRIPTION ACTIONS ---
    fun startTranscription(useGeminiFallback: Boolean = false) {
        viewModelScope.launch {
            _transcriptionError.value = null
            chunkWorkerManager.clearErrors()

            val currentManifest = _audioManifest.value ?: return@launch
            if (currentManifest.chunks.isEmpty()) {
                _transcriptionError.value = "No audio chunks available. Please extract audio in Stage 2."
                return@launch
            }

            if (!useGeminiFallback && !deepgramClient.hasApiKey()) {
                _transcriptionError.value = "Deepgram API key not found. Please add it in Settings → API Keys."
                return@launch
            }

            if (useGeminiFallback && !geminiTranscriber.hasApiKey()) {
                _transcriptionError.value = "Gemini API key not found. Please add it in Settings → API Keys."
                return@launch
            }

            // Start queue processing
            chunkWorkerManager.startTranscription(currentManifest, fallbackGemini = useGeminiFallback)
        }
    }

    fun pauseChunkWorker() {
        chunkWorkerManager.pauseProcessing()
    }

    fun resumeChunkWorker() {
        chunkWorkerManager.resumeProcessing()
    }

    fun retryChunk(chunkIndex: Int) {
        _transcriptionError.value = null
        chunkWorkerManager.clearErrors()
        chunkWorkerManager.retryChunk(chunkIndex)
    }

    fun clearTranscriptionError() {
        _transcriptionError.value = null
        chunkWorkerManager.clearErrors()
    }

    private fun onManifestUpdated(updatedManifest: AudioChunkManifest) {
        viewModelScope.launch {
            val currentProject = _project.value ?: return@launch
            _audioManifest.value = updatedManifest

            val updatedProject = currentProject.copy(audioManifest = updatedManifest)
            withContext(Dispatchers.IO) {
                repository.saveProject(updatedProject)
            }
            _project.value = updatedProject
        }
    }

    private fun onAllChunksCompleted(
        chunkSegmentsMap: Map<Int, List<TranscriptSegment>>,
        isFallback: Boolean
    ) {
        viewModelScope.launch {
            val currentProject = _project.value ?: return@launch
            val manifest = _audioManifest.value ?: return@launch

            withContext(Dispatchers.IO) {
                val mergedTranscript = TranscriptMerger.mergeAndDeduplicate(
                    context = context,
                    projectId = projectId,
                    chunks = manifest.chunks,
                    chunkSegmentsMap = chunkSegmentsMap,
                    isFallbackGemini = isFallback
                )

                val updatedStages = currentProject.stages.toMutableMap().apply {
                    put("Transcript", "completed")
                    put("AI Analysis", "in_progress")
                }

                val updatedProject = currentProject.copy(
                    transcript = mergedTranscript,
                    stages = updatedStages
                )

                repository.saveProject(updatedProject)
                _project.value = updatedProject
                _transcript.value = mergedTranscript
            }
        }
    }

    // Phase 5 Transcript Editing, Speaker Renaming, and Exporting

    /**
     * Updates the text content of a single segment.
     * Text edits never alter timing.
     * Keeps startMs and endMs strictly unchanged.
     */
    fun updateSegmentText(segmentId: String, newText: String) {
        val currentTranscript = _transcript.value ?: return
        val currentProject = _project.value ?: return

        val updatedSegments = currentTranscript.segments.map { segment ->
            if (segment.id == segmentId) {
                // Text edits never alter timing.
                segment.copy(
                    text = newText.trim(),
                    isEdited = true
                )
            } else {
                segment
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val updatedTranscript = TranscriptMerger.updateAndSaveTranscript(
                context = context,
                projectId = projectId,
                updatedSegments = updatedSegments,
                isFallbackGemini = currentTranscript.isFallbackGemini
            )

            val updatedProject = currentProject.copy(transcript = updatedTranscript)
            repository.saveProject(updatedProject)

            withContext(Dispatchers.Main) {
                _transcript.value = updatedTranscript
                _project.value = updatedProject
            }
        }
    }

    /**
     * Renames all segments with the specified speaker label across the entire transcript.
     * Persists changes to Room, transcript.json, and transcript.srt.
     */
    fun renameSpeakerGlobally(oldSpeakerLabel: String, newSpeakerLabel: String) {
        val currentTranscript = _transcript.value ?: return
        val currentProject = _project.value ?: return
        val cleanNewLabel = newSpeakerLabel.trim()
        if (cleanNewLabel.isBlank()) return

        val updatedSegments = currentTranscript.segments.map { segment ->
            if (segment.speakerLabel.equals(oldSpeakerLabel.trim(), ignoreCase = true)) {
                segment.copy(
                    speakerLabel = cleanNewLabel,
                    isEdited = true
                )
            } else {
                segment
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            val updatedTranscript = TranscriptMerger.updateAndSaveTranscript(
                context = context,
                projectId = projectId,
                updatedSegments = updatedSegments,
                isFallbackGemini = currentTranscript.isFallbackGemini
            )

            val updatedProject = currentProject.copy(transcript = updatedTranscript)
            repository.saveProject(updatedProject)

            withContext(Dispatchers.Main) {
                _transcript.value = updatedTranscript
                _project.value = updatedProject
            }
        }
    }

    /**
     * Exports the latest transcript as an SRT file into the Downloads folder.
     */
    fun exportSrt(
        onSuccess: (destinationPath: String) -> Unit,
        onError: (errorMessage: String) -> Unit
    ) {
        val currentTranscript = _transcript.value
        val currentProject = _project.value

        if (currentTranscript == null || currentTranscript.segments.isEmpty()) {
            onError("No transcript available to export.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val srtContent = TranscriptMerger.generateSrt(currentTranscript.segments)
                val projectName = currentProject?.name ?: "ClipGenius"
                TranscriptMerger.exportSrtToDownloads(context, projectName, srtContent)
            }.onSuccess { path ->
                withContext(Dispatchers.Main) {
                    onSuccess(path)
                }
            }.onFailure { ex ->
                withContext(Dispatchers.Main) {
                    onError(ex.localizedMessage ?: "Failed to export SRT")
                }
            }
        }
    }

    // --- Phase 6 AI Virality Analysis & Discovery Operations ---

    fun clearAnalysisError() {
        _analysisError.value = null
    }

    /**
     * Executes Phase 6 viral clip discovery using GeminiClient and AiClipPlanner.
     * Guaranteed: New results replace old ONLY when discovery succeeds.
     * On failure, existing clip candidates remain completely intact.
     */
    fun findClips(requestedCount: Int = 10, minLengthSeconds: Int = 60) {
        val currentTranscript = _transcript.value
        val currentProject = _project.value

        if (currentTranscript == null || currentTranscript.segments.isEmpty()) {
            _analysisError.value = "Transcript is required before discovering clips."
            return
        }

        if (!hasGeminiApiKey()) {
            _analysisError.value = "Gemini API key not found. Add it in Settings → API Keys."
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _isAnalyzingClips.value = true
                _analysisError.value = null
                _analysisProgressText.value = "Preparing transcript for Gemini..."
            }

            try {
                val videoDurationMs = currentProject?.sourceMedia?.durationMs ?: 0L
                val result = aiClipPlanner.discoverClips(
                    projectId = projectId,
                    transcript = currentTranscript,
                    videoDurationMs = videoDurationMs,
                    requestedClipCount = requestedCount,
                    minLengthSeconds = minLengthSeconds,
                    onProgress = { status ->
                        _analysisProgressText.value = status
                    }
                )

                val updatedStages = (currentProject?.stages ?: emptyMap()).toMutableMap().apply {
                    put("AI Analysis", "completed")
                    put("Clip Plan", "in_progress")
                }

                val updatedProject = (currentProject ?: Project(id = projectId, name = "Clip Project")).copy(
                    clipCandidates = result.clips,
                    stages = updatedStages
                )
                repository.saveProject(updatedProject)

                withContext(Dispatchers.Main) {
                    _clipCandidates.value = result.clips
                    _clipsStatusMessage.value = result.statusMessage
                    _project.value = updatedProject
                }

                // Phase 7: Automatically run deterministic timestamp verification right after discovery
                verifyTimestamps(candidatesToVerify = result.clips, minLengthSeconds = minLengthSeconds)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _analysisError.value = e.message ?: "Failed to analyze clips with Gemini."
                }
            } finally {
                withContext(Dispatchers.Main) {
                    _isAnalyzingClips.value = false
                    _analysisProgressText.value = ""
                }
            }
        }
    }

    /**
     * Toggles whether a clip candidate is included for export, and persists to Room.
     */
    fun toggleClipExport(clipId: String, isIncluded: Boolean) {
        val updated = _clipCandidates.value.map {
            if (it.clipId == clipId) it.copy(isIncludedInExport = isIncluded) else it
        }
        _clipCandidates.value = updated

        viewModelScope.launch(Dispatchers.IO) {
            val currentProject = _project.value ?: return@launch
            val updatedProject = currentProject.copy(clipCandidates = updated)
            repository.saveProject(updatedProject)
            withContext(Dispatchers.Main) {
                _project.value = updatedProject
            }
        }
    }

    // --- Phase 7 Timestamp Verification Operations ---

    private fun updateVerificationSummary(clips: List<com.clipgenius.ai.state.VerifiedClip>) {
        if (clips.isEmpty()) {
            _verificationSummary.value = null
            return
        }
        val vCount = clips.count { it.status == com.clipgenius.ai.state.VerificationStatus.VERIFIED }
        val rCount = clips.count { it.status == com.clipgenius.ai.state.VerificationStatus.NEEDS_REVIEW }
        val iCount = clips.count { it.status == com.clipgenius.ai.state.VerificationStatus.INVALID }
        _verificationSummary.value = "$vCount verified, $rCount needs review, $iCount invalid"
    }

    /**
     * Deterministically verifies clip candidate timestamps and quotes against ground-truth transcript.
     * Guaranteed: Pure algorithmic checking, NO AI invocation.
     */
    fun verifyTimestamps(
        candidatesToVerify: List<com.clipgenius.ai.state.ClipCandidate>? = null,
        minLengthSeconds: Int = 60
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _isVerifyingTimestamps.value = true
            }

            val currentCandidates = candidatesToVerify ?: _clipCandidates.value
            val currentTranscript = _transcript.value
            val durationMs = _project.value?.sourceMedia?.durationMs ?: 0L

            val verified = timestampVerifier.verifyAll(
                candidates = currentCandidates,
                transcript = currentTranscript,
                videoDurationMs = durationMs,
                minLengthSeconds = minLengthSeconds
            )

            val currentProject = _project.value
            val hasVerified = verified.any { it.status == com.clipgenius.ai.state.VerificationStatus.VERIFIED && it.isIncludedInExport }

            val updatedStages = (currentProject?.stages ?: emptyMap()).toMutableMap().apply {
                if (hasVerified) {
                    put("Clip Plan", "completed")
                    if (get("Tracking") == "not_started" || get("Tracking") == null) {
                        put("Tracking", "in_progress")
                    }
                }
            }

            val updatedProject = (currentProject ?: Project(id = projectId, name = "Clip Project")).copy(
                verifiedClips = verified,
                stages = updatedStages
            )
            repository.saveProject(updatedProject)

            withContext(Dispatchers.Main) {
                _verifiedClips.value = verified
                _project.value = updatedProject
                updateVerificationSummary(verified)
                _isVerifyingTimestamps.value = false
            }
        }
    }

    /**
     * Accepts the actually-found timestamp for a NEEDS_REVIEW clip.
     */
    fun useFoundTime(clipId: String) {
        val current = _verifiedClips.value
        val target = current.find { it.clipId == clipId } ?: return
        val accepted = timestampVerifier.acceptFoundTime(target)
        val updated = current.map { if (it.clipId == clipId) accepted else it }

        saveVerifiedClipsList(updated)
    }

    /**
     * Manually overrides a clip's timestamps with user input.
     * Validates bounds and duration (checks a-b) and marks clip VERIFIED.
     */
    fun updateClipTimesManually(
        clipId: String,
        startMs: Long,
        endMs: Long,
        hookStartMs: Long,
        hookEndMs: Long,
        minLengthSeconds: Int = 60
    ) {
        val current = _verifiedClips.value
        val target = current.find { it.clipId == clipId } ?: return
        val durationMs = _project.value?.sourceMedia?.durationMs ?: 0L

        val updatedClip = timestampVerifier.verifyManualEdit(
            originalClip = target,
            newStartMs = startMs,
            newEndMs = endMs,
            newHookStartMs = hookStartMs,
            newHookEndMs = hookEndMs,
            videoDurationMs = durationMs,
            minLengthSeconds = minLengthSeconds
        )

        val updated = current.map { if (it.clipId == clipId) updatedClip else it }
        saveVerifiedClipsList(updated)
    }

    /**
     * Discards an invalid or unwanted clip.
     */
    fun discardClip(clipId: String) {
        val current = _verifiedClips.value
        val updated = current.filterNot { it.clipId == clipId }
        saveVerifiedClipsList(updated)
    }

    /**
     * Toggles whether a verified clip is included for export.
     */
    fun toggleVerifiedClipExport(clipId: String, isIncluded: Boolean) {
        val current = _verifiedClips.value
        val updated = current.map {
            if (it.clipId == clipId) it.copy(isIncludedInExport = isIncluded) else it
        }
        saveVerifiedClipsList(updated)
    }

    private fun saveVerifiedClipsList(updated: List<com.clipgenius.ai.state.VerifiedClip>) {
        _verifiedClips.value = updated
        updateVerificationSummary(updated)

        viewModelScope.launch(Dispatchers.IO) {
            val currentProject = _project.value ?: return@launch
            val hasVerified = updated.any { it.status == com.clipgenius.ai.state.VerificationStatus.VERIFIED && it.isIncludedInExport }

            val updatedStages = currentProject.stages.toMutableMap().apply {
                if (hasVerified) {
                    put("Clip Plan", "completed")
                    if (get("Tracking") == "not_started" || get("Tracking") == null) {
                        put("Tracking", "in_progress")
                    }
                }
            }

            val updatedProject = currentProject.copy(
                verifiedClips = updated,
                stages = updatedStages
            )
            repository.saveProject(updatedProject)

            withContext(Dispatchers.Main) {
                _project.value = updatedProject
            }
        }
    }

    // --- Phase 8 Video Cutting Operations ---

    /**
     * Slices an individual verified clip from the project source video.
     */
    fun cutClip(clipId: String) {
        com.clipgenius.ai.cutter.ClipCutterManager.startCutSingleForeground(context, projectId, clipId)
    }

    /**
     * Sequentially slices all eligible verified clips using the foreground service queue.
     */
    fun cutAllVerifiedClips() {
        com.clipgenius.ai.cutter.ClipCutterManager.startCutAllForeground(context, projectId)
    }

    /**
     * Deletes the draft MP4 to free disk storage while keeping the VerifiedClip record.
     */
    fun deleteDraft(clipId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.clipgenius.ai.cutter.ClipCutterManager.deleteDraftClip(context, projectId, clipId, repository)
        }
    }

    /**
     * Adjusts clip boundaries, re-running verifier checks (bounds, start < end, video duration, min duration).
     * Re-cuts the clip only if times changed.
     */
    fun adjustClipTimes(
        clipId: String,
        startMs: Long,
        endMs: Long,
        hookStartMs: Long,
        hookEndMs: Long,
        timesChanged: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            if (timesChanged) {
                // Remove outdated draft
                com.clipgenius.ai.cutter.ClipCutterManager.deleteDraftClip(context, projectId, clipId, repository)
                // Save updated verified times
                updateClipTimesManually(clipId, startMs, endMs, hookStartMs, hookEndMs)
                // Automatically re-cut with new timestamps
                com.clipgenius.ai.cutter.ClipCutterManager.startCutSingleForeground(context, projectId, clipId)
            }
        }
    }

    fun clearStorageError() {
        com.clipgenius.ai.cutter.ClipCutterManager.clearStorageError()
    }

    // --- Phase 9 Face Tracking & 9:16 Vertical Reframing Operations ---

    fun analyzeFaceTrack(clipId: String, forceReanalyze: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            com.clipgenius.ai.tracking.TrackingManager.analyzeClipTrack(
                context = context,
                projectId = projectId,
                clipId = clipId,
                repository = repository,
                forceReanalyze = forceReanalyze
            )
        }
    }

    suspend fun getCropPlan(clip: com.clipgenius.ai.state.VerifiedClip): com.clipgenius.ai.state.ClipCropPlan {
        return com.clipgenius.ai.tracking.TrackingManager.getOrComputeCropPlan(
            context = context,
            projectId = projectId,
            clip = clip,
            transcriptSegments = _transcript.value?.segments ?: emptyList(),
            forceReanalyze = false
        )
    }

    fun updateManualCrop(clipId: String, offsetX: Float, margin: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            com.clipgenius.ai.tracking.TrackingManager.updateManualCrop(
                context = context,
                projectId = projectId,
                clipId = clipId,
                offsetX = offsetX,
                margin = margin,
                repository = repository
            )
        }
    }

    fun renderVerticalDraft(clipId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.clipgenius.ai.tracking.TrackingManager.renderVerticalDraft(
                context = context,
                projectId = projectId,
                clipId = clipId,
                repository = repository
            )
        }
    }

    // --- Phase 10 Multi-Person Layout Operations ---

    suspend fun getLayoutPlan(clip: com.clipgenius.ai.state.VerifiedClip): com.clipgenius.ai.state.ClipLayoutPlan {
        return com.clipgenius.ai.tracking.TrackingManager.getOrComputeLayoutPlan(
            context = context,
            projectId = projectId,
            clip = clip,
            transcriptSegments = _transcript.value?.segments ?: emptyList(),
            forceRecompute = false
        )
    }

    fun updateLayoutSelection(clipId: String, layoutType: com.clipgenius.ai.state.LayoutType) {
        viewModelScope.launch(Dispatchers.IO) {
            com.clipgenius.ai.tracking.TrackingManager.updateLayoutSelection(
                context = context,
                projectId = projectId,
                clipId = clipId,
                newLayoutType = layoutType,
                repository = repository
            )
        }
    }

    fun updateSelectedPersons(clipId: String, selectedIds: List<Int>) {
        viewModelScope.launch(Dispatchers.IO) {
            com.clipgenius.ai.tracking.TrackingManager.updateSelectedPersons(
                context = context,
                projectId = projectId,
                clipId = clipId,
                selectedIds = selectedIds,
                repository = repository
            )
        }
    }

    fun approveLayoutReview(clipId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.clipgenius.ai.tracking.TrackingManager.approveLayoutReview(
                projectId = projectId,
                clipId = clipId,
                repository = repository
            )
        }
    }

    // --- Phase 11 Caption & Preset Operations ---

    fun selectActiveCaptionClip(clipId: String) {
        _activeCaptionClipId.value = clipId
        viewModelScope.launch(Dispatchers.IO) {
            val project = _project.value ?: return@launch
            val clip = project.verifiedClips.find { it.clipId == clipId } ?: return@launch
            
            // Auto-generate captions track if not generated yet
            com.clipgenius.ai.captions.CaptionManager.initializeProjectCaptions(context, project, repository)
            
            // Reload from repo
            val reloadedProject = repository.getProjectById(projectId) ?: return@launch
            val reloadedClip = reloadedProject.verifiedClips.find { it.clipId == clipId } ?: return@launch
            
            val file = com.clipgenius.ai.captions.CaptionGenerator.getCaptionsFile(context, projectId, clipId)
            _activeClipCues.value = com.clipgenius.ai.captions.CaptionGenerator.loadCaptionsFromDisk(file)
        }
    }

    fun updateCaptionCueText(clipId: String, cueId: String, newText: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cues = com.clipgenius.ai.captions.CaptionManager.editCueText(context, projectId, clipId, cueId, newText)
            if (_activeCaptionClipId.value == clipId) {
                _activeClipCues.value = cues
            }
        }
    }

    fun splitCaptionCue(clipId: String, cueId: String, wordIndex: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            val cues = com.clipgenius.ai.captions.CaptionManager.splitCue(context, projectId, clipId, cueId, wordIndex)
            if (_activeCaptionClipId.value == clipId) {
                _activeClipCues.value = cues
            }
        }
    }

    fun mergeCaptionCue(clipId: String, cueId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cues = com.clipgenius.ai.captions.CaptionManager.mergeWithNext(context, projectId, clipId, cueId)
            if (_activeCaptionClipId.value == clipId) {
                _activeClipCues.value = cues
            }
        }
    }

    fun selectPresetForClip(clipId: String, presetId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val project = _project.value ?: return@launch
            val updatedClips = project.verifiedClips.map {
                if (it.clipId == clipId) it.copy(captionPresetId = presetId) else it
            }
            repository.saveProject(project.copy(verifiedClips = updatedClips))
        }
    }

    fun toggleBurnInForClip(clipId: String, burn: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val project = _project.value ?: return@launch
            val updatedClips = project.verifiedClips.map {
                if (it.clipId == clipId) it.copy(burnCaptions = burn) else it
            }
            repository.saveProject(project.copy(verifiedClips = updatedClips))
        }
    }

    fun importCustomPreset(jsonContent: String, onWarnings: (List<String>) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val (preset, warnings) = com.clipgenius.ai.captions.CaptionManager.importPresetFromJson(jsonContent)
            repository.savePreset(preset)
            withContext(Dispatchers.Main) {
                onWarnings(warnings)
            }
        }
    }

    fun exportPreset(preset: com.clipgenius.ai.state.CaptionPreset, onSuccess: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val path = com.clipgenius.ai.captions.CaptionManager.exportPresetToDownloads(preset)
            withContext(Dispatchers.Main) {
                onSuccess(path)
            }
        }
    }

    fun deleteCustomPreset(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePreset(id)
        }
    }

    fun markCaptionsStageComplete() {
        viewModelScope.launch(Dispatchers.IO) {
            com.clipgenius.ai.captions.CaptionManager.checkAndUpdateCaptionsStageCompletion(projectId, repository)
        }
    }

    // --- Phase 12 Effects & Final Export Operations ---

    fun exportAllClips(resumeOnly: Boolean = false) {
        com.clipgenius.ai.render.FinalExporter.exportAllClips(context, projectId, repository, resumeOnly)
    }

    fun renderSingleClip(clipId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.clipgenius.ai.render.FinalExporter.renderSingleClip(context, projectId, clipId, repository)
        }
    }

    fun updateClipFilter(clipId: String, filter: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val project = _project.value ?: return@launch
            val updated = project.verifiedClips.map {
                if (it.clipId == clipId) it.copy(selectedFilter = filter) else it
            }
            repository.saveProject(project.copy(verifiedClips = updated))
        }
    }

    fun toggleClipMirror(clipId: String, mirrored: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val project = _project.value ?: return@launch
            val updated = project.verifiedClips.map {
                if (it.clipId == clipId) it.copy(isMirrored = mirrored) else it
            }
            repository.saveProject(project.copy(verifiedClips = updated))
        }
    }

    fun updateClipReframe(clipId: String, offsetX: Float, zoom: Float) {
        viewModelScope.launch(Dispatchers.IO) {
            val project = _project.value ?: return@launch
            val updated = project.verifiedClips.map {
                if (it.clipId == clipId) it.copy(manualReframeOffsetX = offsetX, manualReframeZoom = zoom) else it
            }
            repository.saveProject(project.copy(verifiedClips = updated))
        }
    }

    fun duplicateCurrentProject(onDuplicated: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val copy = repository.duplicateProject(context, projectId)
            if (copy != null) {
                withContext(Dispatchers.Main) {
                    onDuplicated(copy.id)
                }
            }
        }
    }

    fun deleteCurrentProject(onDeleted: () -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val projectDir = java.io.File(context.filesDir, "projects/$projectId")
            if (projectDir.exists()) {
                projectDir.deleteRecursively()
            }
            repository.deleteProject(projectId)
            withContext(Dispatchers.Main) {
                onDeleted()
            }
        }
    }
}

class ProjectViewModelFactory(
    private val projectId: String,
    private val repository: ProjectRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ProjectViewModel(projectId, repository, context) as T
    }
}
