package com.clipgenius.ai.audio

import android.content.Context
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.clipgenius.ai.state.AudioChunk
import com.clipgenius.ai.state.AudioChunkManifest
import com.clipgenius.ai.state.ChunkStatus
import com.clipgenius.ai.state.SourceMedia
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Phase 3 Audio Extraction & Chunking Engine using FFmpeg-kit.
 * NEVER modifies the original video file.
 */
object AudioExtractor {

    private const val TAG = "AudioExtractor"
    const val CHUNK_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes
    const val CHUNK_DURATION_MS = 20 * 60 * 1000L // 20 minutes
    const val OVERLAP_MS = 60 * 1000L             // 60 seconds

    /**
     * Extracts full audio track from source video and splits into 20-min chunks with 60s overlap if needed.
     */
    fun extractAndChunkAudio(
        context: Context,
        projectId: String,
        sourceMedia: SourceMedia,
        onProgress: (Float, String) -> Unit
    ): AudioChunkManifest {

        if (!sourceMedia.hasAudio) {
            throw IllegalArgumentException("This video has no audio track, so transcription cannot proceed.")
        }

        val videoFile = File(sourceMedia.path)
        if (!videoFile.exists()) {
            throw IOException("Source video file not found at path: ${sourceMedia.path}")
        }

        val audioDir = File(context.filesDir, "projects/$projectId/audio")
        if (!audioDir.exists()) {
            audioDir.mkdirs()
        }

        // Space check before extraction
        val requiredBytes = videoFile.length() / 2
        if (audioDir.freeSpace < requiredBytes) {
            val reqMB = requiredBytes / (1024 * 1024)
            val availMB = audioDir.freeSpace / (1024 * 1024)
            throw IOException("Not enough free storage space for audio extraction. Required: ${reqMB}MB, Available: ${availMB}MB.")
        }

        val fullAudioFile = File(audioDir, "full_audio.m4a")

        // Step 1: Extract full audio
        onProgress(0.1f, "Extracting audio track from video...")

        // Try fast stream copy first (-vn -c:a copy)
        val copyCommand = "-y -i \"${videoFile.absolutePath}\" -vn -c:a copy \"${fullAudioFile.absolutePath}\""
        Log.i(TAG, "Executing FFmpeg stream copy: $copyCommand")
        
        var session = FFmpegKit.execute(copyCommand)
        var isSuccess = ReturnCode.isSuccess(session.returnCode) && fullAudioFile.exists() && fullAudioFile.length() > 0

        if (!isSuccess) {
            Log.w(TAG, "FFmpeg stream copy failed or unsupported. Falling back to AAC 128k re-encode...")
            onProgress(0.2f, "Encoding audio track to AAC 128k...")
            
            val encodeCommand = "-y -i \"${videoFile.absolutePath}\" -vn -c:a aac -b:a 128k \"${fullAudioFile.absolutePath}\""
            session = FFmpegKit.execute(encodeCommand)
            isSuccess = ReturnCode.isSuccess(session.returnCode) && fullAudioFile.exists() && fullAudioFile.length() > 0
            
            if (!isSuccess) {
                Log.e(TAG, "FFmpeg audio extraction failed. Logs: ${session.failStackTrace}")
                if (fullAudioFile.exists()) fullAudioFile.delete()
                throw IOException("Audio extraction failed. Your video file is safe. Tap Retry.")
            }
        }

        onProgress(0.5f, "Audio track extracted successfully. Checking duration...")

        val totalDurationMs = if (sourceMedia.durationMs > 0) sourceMedia.durationMs else 1L

        // Step 2: Chunking logic
        val chunks = mutableListOf<AudioChunk>()

        if (totalDurationMs <= CHUNK_THRESHOLD_MS) {
            // Under or equal 30 minutes -> Single chunk (chunk_00.m4a)
            val singleChunkFile = File(audioDir, "chunk_00.m4a")
            if (fullAudioFile.exists()) {
                fullAudioFile.copyTo(singleChunkFile, overwrite = true)
            }

            chunks.add(
                AudioChunk(
                    index = 0,
                    filePath = singleChunkFile.absolutePath,
                    startMs = 0L,
                    endMs = totalDurationMs,
                    overlapMs = 0L,
                    status = ChunkStatus.QUEUED
                )
            )
            onProgress(1.0f, "Audio prepared as 1 chunk.")
        } else {
            // Over 30 minutes -> Split into 20-min chunks with 60s overlap
            onProgress(0.6f, "Splitting long audio into 20-minute chunks with 60s overlap...")

            var startMs = 0L
            var index = 0

            while (startMs < totalDurationMs) {
                val rawEndMs = startMs + CHUNK_DURATION_MS
                val endMs = rawEndMs.coerceAtMost(totalDurationMs)
                val overlapMs = if (startMs == 0L) 0L else OVERLAP_MS

                val chunkFileName = String.format(Locale.US, "chunk_%02d.m4a", index)
                val chunkFile = File(audioDir, chunkFileName)

                val startSecStr = String.format(Locale.US, "%.3f", startMs / 1000.0)
                val endSecStr = String.format(Locale.US, "%.3f", endMs / 1000.0)

                // Cut chunk with ffmpeg
                val chunkCmd = "-y -ss $startSecStr -to $endSecStr -i \"${fullAudioFile.absolutePath}\" -c copy \"${chunkFile.absolutePath}\""
                var chunkSession = FFmpegKit.execute(chunkCmd)

                if (!ReturnCode.isSuccess(chunkSession.returnCode) || !chunkFile.exists()) {
                    // Fallback to AAC encode for chunk
                    val chunkFallbackCmd = "-y -ss $startSecStr -to $endSecStr -i \"${fullAudioFile.absolutePath}\" -c:a aac -b:a 128k \"${chunkFile.absolutePath}\""
                    chunkSession = FFmpegKit.execute(chunkFallbackCmd)
                }

                chunks.add(
                    AudioChunk(
                        index = index,
                        filePath = chunkFile.absolutePath,
                        startMs = startMs,
                        endMs = endMs,
                        overlapMs = overlapMs,
                        status = ChunkStatus.QUEUED
                    )
                )

                if (endMs >= totalDurationMs) {
                    break
                }

                // Advance start time for next chunk (subtracting overlapMs)
                startMs = endMs - OVERLAP_MS
                index++
            }

            onProgress(1.0f, "Audio split into ${chunks.size} chunks successfully.")
        }

        return AudioChunkManifest(
            fullAudioPath = fullAudioFile.absolutePath,
            totalDurationMs = totalDurationMs,
            chunks = chunks,
            isExtractionComplete = true
        )
    }

    fun formatChunkTimeRange(startMs: Long, endMs: Long): String {
        val startSec = startMs / 1000
        val endSec = endMs / 1000

        val sMin = startSec / 60
        val sSec = startSec % 60

        val eMin = endSec / 60
        val eSec = endSec % 60

        return String.format(Locale.US, "%02d:%02d - %02d:%02d", sMin, sSec, eMin, eSec)
    }
}
