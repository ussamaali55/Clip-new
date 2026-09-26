package com.clipgenius.ai.audio

import android.content.Context
import android.util.Log
import com.clipgenius.ai.state.AudioChunk
import com.clipgenius.ai.state.AudioChunkManifest
import com.clipgenius.ai.state.ChunkStatus
import com.clipgenius.ai.state.TranscriptSegment
import com.clipgenius.ai.transcription.DeepgramAuthException
import com.clipgenius.ai.transcription.DeepgramClient
import com.clipgenius.ai.transcription.DeepgramQuotaException
import com.clipgenius.ai.transcription.GeminiFallbackTranscriber
import com.clipgenius.ai.transcription.TranscriptMerger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 4 Parallel Worker Queue using Kotlin Coroutines & Semaphore.
 * Processes up to MAX_PARALLEL_CHUNKS = 3 concurrently with Deepgram Nova-3 (or Gemini fallback).
 */
class ChunkWorkerManager(
    private val context: Context,
    private val projectId: String,
    private val onManifestUpdated: suspend (AudioChunkManifest) -> Unit,
    private val onAllChunksTranscribed: suspend (Map<Int, List<TranscriptSegment>>, Boolean) -> Unit
) {

    companion object {
        private const val TAG = "ChunkWorkerManager"
        const val MAX_PARALLEL_CHUNKS = 3
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    private val semaphore = Semaphore(MAX_PARALLEL_CHUNKS)

    private val deepgramClient = DeepgramClient(context)
    private val geminiTranscriber = GeminiFallbackTranscriber(context)

    private val _manifestState = MutableStateFlow<AudioChunkManifest?>(null)
    val manifestState: StateFlow<AudioChunkManifest?> = _manifestState.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _quotaError = MutableStateFlow<String?>(null)
    val quotaError: StateFlow<String?> = _quotaError.asStateFlow()

    private val chunkSegmentsMap = ConcurrentHashMap<Int, List<TranscriptSegment>>()
    private var useGeminiFallback = false

    fun startTranscription(manifest: AudioChunkManifest, fallbackGemini: Boolean = false) {
        _manifestState.value = manifest
        _isPaused.value = false
        _isProcessing.value = true
        _authError.value = null
        _quotaError.value = null
        this.useGeminiFallback = fallbackGemini

        job?.cancel()
        job = scope.launch {
            processAllQueuedChunks()
        }
    }

    fun pauseProcessing() {
        _isPaused.value = true
        _isProcessing.value = false
    }

    fun resumeProcessing() {
        if (_manifestState.value == null) return
        _isPaused.value = false
        _isProcessing.value = true

        job?.cancel()
        job = scope.launch {
            processAllQueuedChunks()
        }
    }

    fun retryChunk(chunkIndex: Int) {
        val current = _manifestState.value ?: return
        val updatedChunks = current.chunks.map { chunk ->
            if (chunk.index == chunkIndex) {
                chunk.copy(status = ChunkStatus.QUEUED, errorMessage = null)
            } else {
                chunk
            }
        }
        val updatedManifest = current.copy(chunks = updatedChunks)
        _manifestState.value = updatedManifest

        scope.launch {
            onManifestUpdated(updatedManifest)
        }

        if (!_isProcessing.value && !_isPaused.value) {
            resumeProcessing()
        }
    }

    fun cancelProcessing() {
        job?.cancel()
        _isProcessing.value = false
        _isPaused.value = false
    }

    fun clearErrors() {
        _authError.value = null
        _quotaError.value = null
    }

    private suspend fun processAllQueuedChunks() {
        val current = _manifestState.value ?: return

        // First pre-load any already cached chunk transcripts
        for (chunk in current.chunks) {
            if (!chunkSegmentsMap.containsKey(chunk.index)) {
                val cached = TranscriptMerger.loadChunkTranscript(context, projectId, chunk.index)
                if (cached != null) {
                    chunkSegmentsMap[chunk.index] = cached
                }
            }
        }

        val chunksToProcess = current.chunks.filter { it.status == ChunkStatus.QUEUED }

        val chunkJobs = chunksToProcess.map { chunk ->
            scope.launch {
                semaphore.withPermit {
                    if (_isPaused.value) return@withPermit
                    processChunk(chunk)
                }
            }
        }

        chunkJobs.forEach { it.join() }

        _isProcessing.value = false

        // Check if all chunks in manifest are completed
        val latestManifest = _manifestState.value
        if (latestManifest != null && latestManifest.chunks.isNotEmpty()) {
            val allDone = latestManifest.chunks.all { it.status == ChunkStatus.DONE }
            if (allDone) {
                Log.i(TAG, "All ${latestManifest.chunks.size} chunks successfully transcribed. Triggering merge.")
                onAllChunksTranscribed(chunkSegmentsMap.toMap(), useGeminiFallback)
            }
        }
    }

    private suspend fun processChunk(chunk: AudioChunk) {
        updateChunkStatus(chunk.index, ChunkStatus.PROCESSING)

        if (_isPaused.value) {
            updateChunkStatus(chunk.index, ChunkStatus.QUEUED)
            return
        }

        try {
            val chunkFile = File(chunk.filePath)
            if (!chunkFile.exists() || chunkFile.length() <= 0) {
                throw IllegalStateException("Chunk file missing or empty at ${chunk.filePath}")
            }

            // Check if already transcribed and saved
            val cached = TranscriptMerger.loadChunkTranscript(context, projectId, chunk.index)
            val segments = if (cached != null && cached.isNotEmpty()) {
                Log.i(TAG, "Reusing cached transcript for chunk ${chunk.index}")
                cached
            } else {
                Log.i(TAG, "Transcribing chunk ${chunk.index} (startMs=${chunk.startMs}, fallback=$useGeminiFallback)...")
                val result = if (useGeminiFallback) {
                    geminiTranscriber.transcribeChunk(chunk)
                } else {
                    deepgramClient.transcribeChunk(chunk)
                }
                // Save to chunk_00.json
                TranscriptMerger.saveChunkTranscript(context, projectId, chunk.index, result)
                result
            }

            chunkSegmentsMap[chunk.index] = segments
            updateChunkStatus(chunk.index, ChunkStatus.DONE)
            Log.i(TAG, "Chunk ${chunk.index} transcription complete (${segments.size} segments).")
        } catch (e: DeepgramAuthException) {
            Log.e(TAG, "Auth exception on chunk ${chunk.index}: ${e.message}")
            _authError.value = e.message
            updateChunkStatus(chunk.index, ChunkStatus.FAILED, e.message)
        } catch (e: DeepgramQuotaException) {
            Log.e(TAG, "Quota exception on chunk ${chunk.index}: ${e.message}")
            _quotaError.value = e.message
            updateChunkStatus(chunk.index, ChunkStatus.FAILED, e.message)
        } catch (e: Exception) {
            Log.e(TAG, "Chunk ${chunk.index} failed: ${e.message}", e)
            val errMsg = e.localizedMessage ?: "Transcription failed."
            updateChunkStatus(chunk.index, ChunkStatus.FAILED, errMsg)
        }
    }

    private suspend fun updateChunkStatus(index: Int, status: ChunkStatus, errorMsg: String? = null) {
        val current = _manifestState.value ?: return
        val updatedChunks = current.chunks.map { chunk ->
            if (chunk.index == index) {
                chunk.copy(status = status, errorMessage = errorMsg)
            } else {
                chunk
            }
        }
        val updatedManifest = current.copy(chunks = updatedChunks)
        _manifestState.value = updatedManifest
        onManifestUpdated(updatedManifest)
    }
}
