package com.clipgenius.ai.input

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.clipgenius.ai.state.SourceMedia
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * Phase 2 Video Import & Source Media Handler
 * Manages secure SAF video picking, private app directory copy, storage space validation,
 * and MediaMetadataRetriever media inspection.
 */
object VideoInputHandler {

    /**
     * Copies selected Uri into app-private directory:
     * filesDir/projects/{projectId}/source/original.mp4
     *
     * Original user file is never touched; we work on our private copy.
     */
    fun copyVideoToPrivateStorage(
        context: Context,
        uri: Uri,
        projectId: String,
        onProgress: (Float) -> Unit
    ): File {
        // Persist read URI permission if available
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val totalBytes = getFileSizeFromUri(context, uri)

        // Storage space check before copying
        val targetDir = File(context.filesDir, "projects/$projectId/source")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        val freeSpace = targetDir.freeSpace
        if (totalBytes > 0 && totalBytes > freeSpace) {
            val reqMB = totalBytes / (1024 * 1024)
            val availMB = freeSpace / (1024 * 1024)
            throw IOException("Not enough free storage space on device. Required: ${reqMB}MB, Available: ${availMB}MB.")
        }

        // Original user file is never touched; we work on our private copy.
        val targetFile = File(targetDir, "original.mp4")

        var inputStream: InputStream? = null
        var outputStream: FileOutputStream? = null

        try {
            inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Could not open input stream for selected video URI.")
            outputStream = FileOutputStream(targetFile)

            val buffer = ByteArray(64 * 1024) // 64KB chunk
            var bytesRead: Int
            var totalCopied = 0L

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalCopied += bytesRead
                if (totalBytes > 0) {
                    val progress = (totalCopied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                    onProgress(progress)
                }
            }

            outputStream.flush()
            onProgress(1.0f)
            return targetFile
        } catch (e: Exception) {
            // Cleanup partial file on failure or cancellation
            if (targetFile.exists()) {
                targetFile.delete()
            }
            throw e
        } finally {
            runCatching { inputStream?.close() }
            runCatching { outputStream?.close() }
        }
    }

    /**
     * Inspects media metadata using MediaMetadataRetriever.
     */
    fun inspectMediaFile(context: Context, videoFile: File, originalFileName: String? = null): SourceMedia {
        if (!videoFile.exists() || videoFile.length() <= 0) {
            throw IllegalArgumentException("Could not read this video file. It may be corrupt or in an unsupported format.")
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            if (durationMs <= 0) {
                throw IllegalArgumentException("Could not read this video file. It may be corrupt or in an unsupported format.")
            }

            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val width = widthStr?.toIntOrNull() ?: 0
            val height = heightStr?.toIntOrNull() ?: 0

            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val rotation = rotationStr?.toIntOrNull() ?: 0

            val frameRateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
            val frameRate = frameRateStr?.toFloatOrNull() ?: 30.0f

            val hasAudioStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val hasAudio = hasAudioStr == "yes" || hasAudioStr == "true"

            val videoMime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video/mp4"

            val displayName = originalFileName ?: videoFile.name

            return SourceMedia(
                path = videoFile.absolutePath,
                fileName = displayName,
                durationMs = durationMs,
                width = width,
                height = height,
                rotation = rotation,
                frameRate = frameRate,
                hasAudio = hasAudio,
                codec = videoMime,
                audioCodec = if (hasAudio) "AAC / Audio Track" else "None",
                fileSizeBytes = videoFile.length()
            )
        } catch (e: Exception) {
            if (e is IllegalArgumentException) throw e
            throw IllegalArgumentException("Could not read this video file. It may be corrupt or in an unsupported format.", e)
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Extracts a frame bitmap thumbnail from the video file.
     */
    fun extractThumbnail(videoFile: File): Bitmap? {
        if (!videoFile.exists()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Deletes the private copy file and working folder for a project.
     */
    fun deletePrivateCopy(context: Context, projectId: String) {
        val targetDir = File(context.filesDir, "projects/$projectId/source")
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
    }

    /**
     * Extracts display name from Uri.
     */
    fun getFileNameFromUri(context: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        name = cursor.getString(index)
                    }
                }
            }
        }
        if (name == null) {
            name = uri.path?.let { File(it).name }
        }
        return name ?: "video.mp4"
    }

    private fun getFileSizeFromUri(context: Context, uri: Uri): Long {
        var size = -1L
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0) {
                        size = cursor.getLong(index)
                    }
                }
            }
        }
        if (size <= 0) {
            runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                    size = afd.length
                }
            }
        }
        return size
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    fun formatFileSize(bytes: Long): String {
        val mb = bytes.toDouble() / (1024 * 1024)
        return if (mb >= 1024) {
            String.format(Locale.US, "%.2f GB", mb / 1024)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    fun formatResolution(width: Int, height: Int, rotation: Int): String {
        return if (rotation == 90 || rotation == 270) {
            "${height}x${width}"
        } else {
            "${width}x${height}"
        }
    }
}
