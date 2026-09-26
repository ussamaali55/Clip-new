package com.clipgenius.ai.transcription

import android.content.Context
import com.clipgenius.ai.state.AudioChunk
import com.clipgenius.ai.state.ProjectTranscript
import com.clipgenius.ai.state.TranscriptSegment
import com.clipgenius.ai.state.TranscriptWord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Phase 4 Transcript Merge & Deduplication Engine.
 * Concatenates chunk transcripts, drops words/segments falling inside the 60s overlap window,
 * re-indexes segment IDs, and exports transcript.json + transcript.srt.
 */
object TranscriptMerger {

    fun mergeAndDeduplicate(
        context: Context,
        projectId: String,
        chunks: List<AudioChunk>,
        chunkSegmentsMap: Map<Int, List<TranscriptSegment>>,
        isFallbackGemini: Boolean
    ): ProjectTranscript {
        val transcriptDir = File(context.filesDir, "projects/$projectId/transcript")
        transcriptDir.mkdirs()

        val sortedChunks = chunks.sortedBy { it.index }
        val mergedSegments = mutableListOf<TranscriptSegment>()

        for (chunk in sortedChunks) {
            val segments = chunkSegmentsMap[chunk.index] ?: emptyList()
            if (chunk.index == 0 || chunk.overlapMs <= 0L) {
                mergedSegments.addAll(segments)
            } else {
                val overlapBoundaryMs = chunk.startMs + chunk.overlapMs
                for (segment in segments) {
                    if (segment.startMs < overlapBoundaryMs) {
                        // Drop words falling inside the already-covered overlap window
                        val remainingWords = segment.words.filter { it.startMs >= overlapBoundaryMs }
                        if (remainingWords.isNotEmpty()) {
                            mergedSegments.add(
                                segment.copy(
                                    startMs = remainingWords.first().startMs,
                                    text = remainingWords.joinToString(" ") { it.word },
                                    words = remainingWords
                                )
                            )
                        }
                    } else {
                        mergedSegments.add(segment)
                    }
                }
            }
        }

        // Sort by startMs and re-index IDs
        val sortedSegments = mergedSegments.sortedBy { it.startMs }.mapIndexed { index, seg ->
            seg.copy(id = "seg_${String.format(Locale.US, "%04d", index + 1)}")
        }

        val totalWords = sortedSegments.sumOf { seg ->
            if (seg.words.isNotEmpty()) seg.words.size else seg.text.split(Regex("\\s+")).count { it.isNotBlank() }
        }

        val totalSpeakers = sortedSegments.mapNotNull { it.speakerLabel }.distinct().size

        // Export transcript.json
        val jsonFile = File(transcriptDir, "transcript.json")
        val jsonString = serializeTranscriptJson(sortedSegments, isFallbackGemini, totalWords, totalSpeakers)
        jsonFile.writeText(jsonString)

        // Export transcript.srt
        val srtFile = File(transcriptDir, "transcript.srt")
        val srtString = generateSrt(sortedSegments)
        srtFile.writeText(srtString)

        return ProjectTranscript(
            segments = sortedSegments,
            isFallbackGemini = isFallbackGemini,
            totalWords = totalWords,
            totalSpeakers = totalSpeakers,
            srtPath = srtFile.absolutePath,
            jsonPath = jsonFile.absolutePath,
            completedAt = System.currentTimeMillis()
        )
    }

    fun saveChunkTranscript(
        context: Context,
        projectId: String,
        chunkIndex: Int,
        segments: List<TranscriptSegment>
    ): File {
        val transcriptDir = File(context.filesDir, "projects/$projectId/transcript")
        transcriptDir.mkdirs()

        val chunkFileName = String.format(Locale.US, "chunk_%02d.json", chunkIndex)
        val chunkFile = File(transcriptDir, chunkFileName)

        val root = JSONObject()
        root.put("chunkIndex", chunkIndex)
        root.put("segmentCount", segments.size)

        val segArray = JSONArray()
        for (seg in segments) {
            val segObj = JSONObject()
            segObj.put("id", seg.id)
            segObj.put("startMs", seg.startMs)
            segObj.put("endMs", seg.endMs)
            segObj.put("text", seg.text)
            if (seg.speakerLabel != null) segObj.put("speakerLabel", seg.speakerLabel)

            val wordsArr = JSONArray()
            for (w in seg.words) {
                val wObj = JSONObject()
                wObj.put("word", w.word)
                wObj.put("startMs", w.startMs)
                wObj.put("endMs", w.endMs)
                if (w.speaker != null) wObj.put("speaker", w.speaker)
                if (w.confidence != null) wObj.put("confidence", w.confidence.toDouble())
                wordsArr.put(wObj)
            }
            segObj.put("words", wordsArr)
            segArray.put(segObj)
        }
        root.put("segments", segArray)

        chunkFile.writeText(root.toString(2))
        return chunkFile
    }

    fun loadChunkTranscript(
        context: Context,
        projectId: String,
        chunkIndex: Int
    ): List<TranscriptSegment>? {
        val transcriptDir = File(context.filesDir, "projects/$projectId/transcript")
        val chunkFileName = String.format(Locale.US, "chunk_%02d.json", chunkIndex)
        val chunkFile = File(transcriptDir, chunkFileName)
        if (!chunkFile.exists()) return null

        return runCatching {
            val root = JSONObject(chunkFile.readText())
            val segArray = root.optJSONArray("segments") ?: return null
            val segments = mutableListOf<TranscriptSegment>()
            for (i in 0 until segArray.length()) {
                val obj = segArray.getJSONObject(i)
                val wordsList = mutableListOf<TranscriptWord>()
                val wordsArr = obj.optJSONArray("words")
                if (wordsArr != null) {
                    for (j in 0 until wordsArr.length()) {
                        val wObj = wordsArr.getJSONObject(j)
                        wordsList.add(
                            TranscriptWord(
                                word = wObj.optString("word", ""),
                                startMs = wObj.optLong("startMs", 0L),
                                endMs = wObj.optLong("endMs", 0L),
                                speaker = if (wObj.has("speaker")) wObj.optInt("speaker") else null,
                                confidence = if (wObj.has("confidence")) wObj.optDouble("confidence").toFloat() else null
                            )
                        )
                    }
                }
                segments.add(
                    TranscriptSegment(
                        id = obj.optString("id"),
                        startMs = obj.optLong("startMs"),
                        endMs = obj.optLong("endMs"),
                        text = obj.optString("text"),
                        speakerLabel = if (obj.has("speakerLabel")) obj.optString("speakerLabel") else null,
                        words = wordsList,
                        isEdited = obj.optBoolean("isEdited", false)
                    )
                )
            }
            segments
        }.getOrNull()
    }

    fun updateAndSaveTranscript(
        context: Context,
        projectId: String,
        updatedSegments: List<TranscriptSegment>,
        isFallbackGemini: Boolean
    ): ProjectTranscript {
        val transcriptDir = File(context.filesDir, "projects/$projectId/transcript")
        transcriptDir.mkdirs()

        val totalWords = updatedSegments.sumOf { seg ->
            if (seg.words.isNotEmpty()) seg.words.size else seg.text.split(Regex("\\s+")).count { it.isNotBlank() }
        }
        val totalSpeakers = updatedSegments.mapNotNull { it.speakerLabel }.distinct().size

        // Export transcript.json
        val jsonFile = File(transcriptDir, "transcript.json")
        val jsonString = serializeTranscriptJson(updatedSegments, isFallbackGemini, totalWords, totalSpeakers)
        jsonFile.writeText(jsonString)

        // Export transcript.srt
        val srtFile = File(transcriptDir, "transcript.srt")
        val srtString = generateSrt(updatedSegments)
        srtFile.writeText(srtString)

        return ProjectTranscript(
            segments = updatedSegments,
            isFallbackGemini = isFallbackGemini,
            totalWords = totalWords,
            totalSpeakers = totalSpeakers,
            srtPath = srtFile.absolutePath,
            jsonPath = jsonFile.absolutePath,
            completedAt = System.currentTimeMillis()
        )
    }

    /**
     * Exports the transcript as an SRT file to the user's Downloads folder.
     * Uses MediaStore on Android 10+ (API 29+) or public Downloads directory.
     * Returns the human-readable destination path or filename.
     */
    fun exportSrtToDownloads(
        context: Context,
        projectName: String,
        srtContent: String
    ): String {
        val sanitizedName = projectName.ifBlank { "transcript" }
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val fileName = if (sanitizedName.endsWith(".srt", ignoreCase = true)) sanitizedName else "$sanitizedName.srt"

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val contentValues = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("Failed to create download entry in MediaStore")
            resolver.openOutputStream(uri)?.use { outputStream ->
                outputStream.write(srtContent.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            } ?: throw IOException("Failed to write SRT content to Downloads")
            return "Downloads/$fileName"
        } else {
            val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs()
            }
            val targetFile = File(downloadsDir, fileName)
            targetFile.writeText(srtContent, Charsets.UTF_8)
            return targetFile.absolutePath
        }
    }

    private fun serializeTranscriptJson(
        segments: List<TranscriptSegment>,
        isFallbackGemini: Boolean,
        totalWords: Int,
        totalSpeakers: Int
    ): String {
        val root = JSONObject()
        root.put("isFallbackGemini", isFallbackGemini)
        root.put("totalWords", totalWords)
        root.put("totalSpeakers", totalSpeakers)
        root.put("segmentCount", segments.size)

        val segArray = JSONArray()
        for (seg in segments) {
            val obj = JSONObject()
            obj.put("id", seg.id)
            obj.put("startMs", seg.startMs)
            obj.put("endMs", seg.endMs)
            obj.put("text", seg.text)
            obj.put("isEdited", seg.isEdited)
            if (seg.speakerLabel != null) obj.put("speakerLabel", seg.speakerLabel)

            val wordsArr = JSONArray()
            for (w in seg.words) {
                val wObj = JSONObject()
                wObj.put("word", w.word)
                wObj.put("startMs", w.startMs)
                wObj.put("endMs", w.endMs)
                if (w.speaker != null) wObj.put("speaker", w.speaker)
                if (w.confidence != null) wObj.put("confidence", w.confidence.toDouble())
                wordsArr.put(wObj)
            }
            obj.put("words", wordsArr)
            segArray.put(obj)
        }
        root.put("segments", segArray)
        return root.toString(2)
    }

    fun generateSrt(segments: List<TranscriptSegment>): String {
        val sb = StringBuilder()
        segments.forEachIndexed { index, seg ->
            sb.append(index + 1).append("\n")
            sb.append(formatSrtTimestamp(seg.startMs))
                .append(" --> ")
                .append(formatSrtTimestamp(seg.endMs))
                .append("\n")

            val prefix = if (!seg.speakerLabel.isNullOrBlank()) "[${seg.speakerLabel}] " else ""
            sb.append(prefix).append(seg.text).append("\n\n")
        }
        return sb.toString().trimEnd() + "\n"
    }

    fun formatSrtTimestamp(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}
