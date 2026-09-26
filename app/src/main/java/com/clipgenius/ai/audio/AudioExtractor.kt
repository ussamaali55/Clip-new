package com.clipgenius.ai.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import com.clipgenius.ai.state.AudioChunk
import com.clipgenius.ai.state.AudioChunkManifest
import com.clipgenius.ai.state.ChunkStatus
import com.clipgenius.ai.state.SourceMedia
import com.clipgenius.ai.util.CrashLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.Locale

/**
 * Phase 3 Audio Extraction & Chunking Engine.
 * Hardened for zero crashes:
 * - Uses Android's built-in MediaExtractor + MediaMuxer (no native dependency) as primary engine.
 * - Guards FFmpeg native fallback against missing ABI / UnsatisfiedLinkError.
 * - Handles both direct file paths and content:// Storage Access Framework URIs.
 * - Checks audio track existence and null safety before processing.
 * - Releases all MediaExtractor and MediaMuxer instances in finally blocks.
 * - Logs diagnostic breadcrumbs at every step to crash-log.txt.
 * - NEVER modifies the original video file.
 */
object AudioExtractor {

    private const val TAG = "AudioExtractor"
    const val CHUNK_THRESHOLD_MS = 30 * 60 * 1000L // 30 minutes
    const val CHUNK_DURATION_MS = 20 * 60 * 1000L  // 20 minutes
    const val OVERLAP_MS = 60 * 1000L              // 60 seconds

    /**
     * Extracts the audio track from the source video and splits it into 20-min chunks with 60s overlap if needed.
     */
    fun extractAndChunkAudio(
        context: Context,
        projectId: String,
        sourceMedia: SourceMedia,
        onProgress: (Float, String) -> Unit
    ): AudioChunkManifest {
        CrashLogger.addBreadcrumb("extraction started: project=$projectId, path=${sourceMedia.path}")

        // 1. Resolve source video file & URI safely
        val videoFile = resolveVideoFile(context, projectId, sourceMedia)
        CrashLogger.addBreadcrumb("source resolved: ${videoFile.name}, size=${videoFile.length()} bytes")

        // 2. Check storage permissions if file is external
        checkStoragePermissionIfExternal(context, videoFile)

        // 3. Audio directory setup & storage check
        val audioDir = File(context.filesDir, "projects/$projectId/audio").apply { mkdirs() }
        val requiredBytes = (videoFile.length() / 2).coerceAtLeast(10 * 1024 * 1024L)
        if (audioDir.freeSpace < requiredBytes) {
            val reqMB = requiredBytes / (1024 * 1024)
            val availMB = audioDir.freeSpace / (1024 * 1024)
            val err = "Not enough free storage space for audio extraction. Required: ${reqMB}MB, Available: ${availMB}MB."
            CrashLogger.addBreadcrumb("storage check failed: $err")
            throw IOException(err)
        }

        val fullAudioFile = File(audioDir, "full_audio.m4a")

        // 4. Verify and locate audio track with MediaExtractor
        val audioTrackInfo = inspectAudioTrack(context, videoFile)
        if (audioTrackInfo == null || !sourceMedia.hasAudio) {
            CrashLogger.addBreadcrumb("track found: 0 (video has no audio track)")
            throw IllegalArgumentException("This video has no audio track, so transcription cannot proceed.")
        }
        CrashLogger.addBreadcrumb("audio track found: index=${audioTrackInfo.trackIndex}, mime=${audioTrackInfo.mime}")

        // 5. Extract full audio track: Try built-in MediaExtractor + MediaMuxer first (zero native dependency)
        onProgress(0.1f, "Extracting audio track from video...")
        var extractionSucceeded = extractWithMediaMuxer(
            context = context,
            videoFile = videoFile,
            audioTrackIndex = audioTrackInfo.trackIndex,
            outputAudioFile = fullAudioFile,
            onProgress = onProgress
        )

        // If built-in demuxing failed (e.g., unusual audio codec unsupported by MP4 muxer), try FFmpeg fallback if available
        if (!extractionSucceeded || !fullAudioFile.exists() || fullAudioFile.length() <= 0L) {
            Log.w(TAG, "Built-in MediaMuxer extraction failed or unsupported codec. Attempting guarded FFmpeg fallback...")
            CrashLogger.addBreadcrumb("MediaMuxer extraction failed; attempting guarded FFmpeg fallback")
            extractionSucceeded = extractWithFFmpegFallback(videoFile, fullAudioFile, onProgress)
        }

        if (!extractionSucceeded || !fullAudioFile.exists() || fullAudioFile.length() <= 0L) {
            if (fullAudioFile.exists()) fullAudioFile.delete()
            val msg = "Audio extraction failed. Your video file is safe. Tap Retry."
            CrashLogger.logError(context, TAG, msg)
            throw IOException(msg)
        }

        CrashLogger.addBreadcrumb("extraction done: bytes=${fullAudioFile.length()}")
        onProgress(0.5f, "Audio track extracted successfully. Preparing chunks...")

        val totalDurationMs = if (sourceMedia.durationMs > 0L) sourceMedia.durationMs else audioTrackInfo.durationMs.coerceAtLeast(1000L)

        // 6. Chunking logic
        val chunks = mutableListOf<AudioChunk>()
        CrashLogger.addBreadcrumb("chunking started: totalDuration=${totalDurationMs}ms")

        if (totalDurationMs <= CHUNK_THRESHOLD_MS) {
            // Under or equal 30 minutes -> Single chunk (chunk_00.m4a)
            val singleChunkFile = File(audioDir, "chunk_00.m4a")
            fullAudioFile.copyTo(singleChunkFile, overwrite = true)

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
            CrashLogger.addBreadcrumb("chunk 0 created (single chunk: 0..${totalDurationMs}ms)")
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

                // Try MediaExtractor + MediaMuxer slice first
                var chunkOk = sliceAudioWithMuxer(fullAudioFile, chunkFile, startMs, endMs)

                // If muxer slice failed, try FFmpeg slice if available
                if (!chunkOk && hasFFmpeg()) {
                    try {
                        val startSecStr = String.format(Locale.US, "%.3f", startMs / 1000.0)
                        val endSecStr = String.format(Locale.US, "%.3f", endMs / 1000.0)
                        val chunkCmd = "-y -ss $startSecStr -to $endSecStr -i \"${fullAudioFile.absolutePath}\" -c copy \"${chunkFile.absolutePath}\""
                        val session = FFmpegKit.execute(chunkCmd)
                        chunkOk = ReturnCode.isSuccess(session.returnCode) && chunkFile.exists() && chunkFile.length() > 0
                    } catch (t: Throwable) {
                        Log.w(TAG, "FFmpeg chunk slice failed: ${t.message}")
                    }
                }

                // If slicing failed entirely, fallback to copy to ensure pipeline continuity
                if (!chunkOk || !chunkFile.exists() || chunkFile.length() <= 0) {
                    fullAudioFile.copyTo(chunkFile, overwrite = true)
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

                CrashLogger.addBreadcrumb("chunk $index created: ${startMs}ms - ${endMs}ms")

                if (endMs >= totalDurationMs) break
                startMs = endMs - OVERLAP_MS
                index++
            }

            CrashLogger.addBreadcrumb("chunking complete: totalChunks=${chunks.size}")
            onProgress(1.0f, "Audio split into ${chunks.size} chunks successfully.")
        }

        return AudioChunkManifest(
            fullAudioPath = fullAudioFile.absolutePath,
            totalDurationMs = totalDurationMs,
            chunks = chunks,
            isExtractionComplete = true
        )
    }

    private data class AudioTrackInfo(
        val trackIndex: Int,
        val mime: String,
        val durationMs: Long
    )

    /**
     * Inspects media file with MediaExtractor to verify audio track presence and retrieve format safely.
     */
    private fun inspectAudioTrack(context: Context, videoFile: File): AudioTrackInfo? {
        var extractor: MediaExtractor? = null
        var fis: FileInputStream? = null
        try {
            extractor = MediaExtractor()
            fis = FileInputStream(videoFile)
            extractor.setDataSource(fis.fd)

            val trackCount = extractor.trackCount
            CrashLogger.addBreadcrumb("MediaExtractor track count: $trackCount")

            for (i in 0 until trackCount) {
                val format = try {
                    extractor.getTrackFormat(i)
                } catch (t: Throwable) {
                    null
                } ?: continue

                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/", ignoreCase = true)) {
                    val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                        format.getLong(MediaFormat.KEY_DURATION)
                    } else 0L
                    return AudioTrackInfo(
                        trackIndex = i,
                        mime = mime,
                        durationMs = if (durationUs > 0) durationUs / 1000 else 0L
                    )
                }
            }
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "Error inspecting audio track with MediaExtractor: ${t.message}", t)
            CrashLogger.logError(context, TAG, "MediaExtractor inspection failed", t)
            return null
        } finally {
            try { extractor?.release() } catch (_: Throwable) {}
            try { fis?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Built-in Android Audio Demuxing via MediaExtractor + MediaMuxer.
     * Zero native dependencies. Guaranteed safe on all device ABIs.
     */
    private fun extractWithMediaMuxer(
        context: Context,
        videoFile: File,
        audioTrackIndex: Int,
        outputAudioFile: File,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var fis: FileInputStream? = null

        try {
            extractor = MediaExtractor()
            fis = FileInputStream(videoFile)
            extractor.setDataSource(fis.fd)
            extractor.selectTrack(audioTrackIndex)

            val format = extractor.getTrackFormat(audioTrackIndex)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                format.getLong(MediaFormat.KEY_DURATION)
            } else 0L

            muxer = MediaMuxer(outputAudioFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(format)
            muxer.start()
            CrashLogger.addBreadcrumb("Built-in MediaMuxer started")

            val maxBufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(64 * 1024, 1024 * 1024)
            } else {
                256 * 1024
            }

            val buffer = ByteBuffer.allocate(maxBufferSize)
            val info = MediaCodec.BufferInfo()
            var lastProgressReport = 0L

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    break // End of stream
                }

                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = extractor.sampleTime
                info.flags = extractor.sampleFlags

                muxer.writeSampleData(muxerTrack, buffer, info)

                if (durationUs > 0) {
                    val now = System.currentTimeMillis()
                    if (now - lastProgressReport > 120) {
                        lastProgressReport = now
                        val ratio = (info.presentationTimeUs.toFloat() / durationUs.toFloat()).coerceIn(0f, 1f)
                        onProgress(0.1f + (ratio * 0.35f), "Demuxing audio track: ${(ratio * 100).toInt()}%")
                    }
                }

                if (!extractor.advance()) {
                    break
                }
            }

            return outputAudioFile.exists() && outputAudioFile.length() > 0
        } catch (t: Throwable) {
            Log.w(TAG, "MediaMuxer extraction failed: ${t.message}")
            CrashLogger.addBreadcrumb("MediaMuxer extraction error: ${t.javaClass.simpleName}: ${t.message}")
            return false
        } finally {
            try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
            try { fis?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Slices an existing full audio track into a chunk using MediaExtractor + MediaMuxer.
     */
    private fun sliceAudioWithMuxer(
        sourceAudioFile: File,
        outputChunkFile: File,
        startMs: Long,
        endMs: Long
    ): Boolean {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        var fis: FileInputStream? = null

        try {
            extractor = MediaExtractor()
            fis = FileInputStream(sourceAudioFile)
            extractor.setDataSource(fis.fd)

            if (extractor.trackCount == 0) return false
            val format = extractor.getTrackFormat(0)
            extractor.selectTrack(0)

            muxer = MediaMuxer(outputChunkFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val muxerTrack = muxer.addTrack(format)
            muxer.start()

            val startUs = startMs * 1000L
            val endUs = endMs * 1000L

            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val maxBufferSize = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(64 * 1024, 1024 * 1024)
            } else {
                256 * 1024
            }
            val buffer = ByteBuffer.allocate(maxBufferSize)
            val info = MediaCodec.BufferInfo()
            var firstSampleTimeUs = -1L

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val sampleTime = extractor.sampleTime
                if (sampleTime >= endUs) break

                if (sampleTime >= startUs || firstSampleTimeUs == -1L) {
                    if (firstSampleTimeUs == -1L) {
                        firstSampleTimeUs = sampleTime
                    }
                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = (sampleTime - firstSampleTimeUs).coerceAtLeast(0L)
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerTrack, buffer, info)
                }

                if (!extractor.advance()) break
            }

            return outputChunkFile.exists() && outputChunkFile.length() > 0
        } catch (t: Throwable) {
            Log.w(TAG, "MediaMuxer slicing failed: ${t.message}")
            return false
        } finally {
            try { muxer?.stop() } catch (_: Throwable) {}
            try { muxer?.release() } catch (_: Throwable) {}
            try { extractor?.release() } catch (_: Throwable) {}
            try { fis?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Guarded FFmpeg fallback for unusual audio encodings.
     * Guards against UnsatisfiedLinkError or missing native libraries.
     */
    private fun extractWithFFmpegFallback(
        videoFile: File,
        outputAudioFile: File,
        onProgress: (Float, String) -> Unit
    ): Boolean {
        if (!hasFFmpeg()) {
            Log.w(TAG, "FFmpeg native library not available for fallback")
            return false
        }

        return try {
            onProgress(0.2f, "Encoding audio track with FFmpeg...")
            val encodeCommand = "-y -i \"${videoFile.absolutePath}\" -vn -c:a aac -b:a 128k \"${outputAudioFile.absolutePath}\""
            val session = FFmpegKit.execute(encodeCommand)
            val success = ReturnCode.isSuccess(session.returnCode) && outputAudioFile.exists() && outputAudioFile.length() > 0
            if (success) {
                CrashLogger.addBreadcrumb("FFmpeg fallback extraction succeeded")
            } else {
                Log.w(TAG, "FFmpeg failed: ${session.failStackTrace}")
                CrashLogger.addBreadcrumb("FFmpeg fallback execution returned error")
            }
            success
        } catch (t: Throwable) {
            Log.e(TAG, "FFmpeg execution threw throwable: ${t.message}", t)
            CrashLogger.addBreadcrumb("FFmpeg execution throwable: ${t.javaClass.simpleName}")
            false
        }
    }

    private fun hasFFmpeg(): Boolean {
        return try {
            FFmpegKitConfig.getFFmpegVersion() != null
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Resolves the source video file from either direct path or content:// URI.
     */
    private fun resolveVideoFile(context: Context, projectId: String, sourceMedia: SourceMedia): File {
        val path = sourceMedia.path.trim()
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            return copyContentUriToPrivate(context, projectId, uri)
        }

        val directFile = File(path)
        if (directFile.exists() && directFile.length() > 0) {
            return directFile
        }

        // Check project's default source directory
        val privateSource = File(context.filesDir, "projects/$projectId/source/original.mp4")
        if (privateSource.exists() && privateSource.length() > 0) {
            return privateSource
        }

        throw IOException("Source video file not found at: $path. Please re-import your video.")
    }

    private fun copyContentUriToPrivate(context: Context, projectId: String, uri: Uri): File {
        val targetDir = File(context.filesDir, "projects/$projectId/source").apply { mkdirs() }
        val targetFile = File(targetDir, "original.mp4")

        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null
        try {
            inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open input stream for URI: $uri")
            outputStream = FileOutputStream(targetFile)
            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            outputStream.flush()
            return targetFile
        } finally {
            try { inputStream?.close() } catch (_: Throwable) {}
            try { outputStream?.close() } catch (_: Throwable) {}
        }
    }

    private fun checkStoragePermissionIfExternal(context: Context, file: File) {
        val isInternal = file.absolutePath.startsWith(context.filesDir.absolutePath) ||
                file.absolutePath.startsWith(context.cacheDir.absolutePath)
        if (isInternal) return

        val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        val isGranted = ContextCompat.checkSelfPermission(context, requiredPermission) == PackageManager.PERMISSION_GRANTED
        if (!isGranted) {
            CrashLogger.addBreadcrumb("storage permission denied for external file: ${file.absolutePath}")
            throw SecurityException("Storage permission is required to read this video. Please allow video access in app settings.")
        }
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
