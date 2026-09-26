package com.clipgenius.ai.aiplanner

import android.content.Context
import com.clipgenius.ai.data.ProjectTypeConverters
import com.clipgenius.ai.state.ClipCandidate
import com.clipgenius.ai.state.ProjectTranscript
import com.clipgenius.ai.state.SourceRange
import com.clipgenius.ai.state.TranscriptSegment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Phase 6: Gemini AI Virality Analysis & Clip Planning.
 * Prepares compact transcript prompts, chunks large transcripts, calls GeminiClient,
 * parses & validates viral clip candidates, and dedupes overlapping clips.
 */
class AiClipPlanner(private val context: Context) {

    companion object {
        /**
         * Exact scoring prompt template. Configurable constant so it can be tuned later.
         */
        const val SCORING_PROMPT_TEMPLATE =
            "You are a viral short-form video editor. Analyze this video transcript with timestamps. Find {N} standalone clip-worthy moments, each at least {MIN_LEN} seconds long. Score each 0-100 using: hook strength in first 3 seconds (25%), emotional peak or strong opinion (20%), visual/story appeal (20%), trend relevance (15%), ideal length (10%), shareability (10%). A clip needs a HOOK (grab attention), STORY/DEVELOPMENT (builds meaning), and PAYOFF (conclusion or punchline) — these may come from DIFFERENT parts of the video; if so, list multiple source ranges in order. The strongest hook moment may occur AFTER the clip start — report it separately as hookStart/hookEnd. Return STRICT JSON only, no other text: {\"clips\":[{\"clipId\":\"c1\",\"sourceRanges\":[{\"start\":\"MM:SS\",\"end\":\"MM:SS\"}],\"hookStart\":\"MM:SS\",\"hookEnd\":\"MM:SS\",\"transcriptText\":\"exact quotes from the transcript\",\"score\":87,\"title\":\"short catchy title\",\"hookSentence\":\"the single most gripping sentence\",\"viralityReason\":\"one line: why this will go viral\",\"caption\":\"suggested post caption\",\"hashtags\":[\"1 broad viral tag\",\"2 niche tags\",\"2 topic tags\"]}]}. Every timestamp MUST come from the provided transcript — never invent times."

        private const val MAX_TRANSCRIPT_BYTES_SINGLE_CALL = 400 * 1024 // ~400 KB
        private const val WINDOW_DURATION_MS = 30 * 60 * 1000L // 30 minutes
        private const val OVERLAP_DURATION_MS = 2 * 60 * 1000L // 2 minutes
    }

    private val geminiClient = GeminiClient(context)

    data class DiscoveryResult(
        val clips: List<ClipCandidate>,
        val requestedCount: Int,
        val validCount: Int,
        val statusMessage: String
    )

    /**
     * Executes the viral clip discovery.
     * @param projectId Project UUID
     * @param transcript Merged ProjectTranscript
     * @param videoDurationMs Video duration in milliseconds (0 if unknown)
     * @param requestedClipCount Number of clips to find (default 10, range 1-30)
     * @param minLengthSeconds Minimum clip length in seconds (30, 60, or 90)
     * @param onProgress Callback receiving progress status text e.g. "Analyzing part 2 of 5..."
     */
    suspend fun discoverClips(
        projectId: String,
        transcript: ProjectTranscript,
        videoDurationMs: Long,
        requestedClipCount: Int = 10,
        minLengthSeconds: Int = 60,
        onProgress: (String) -> Unit
    ): DiscoveryResult = withContext(Dispatchers.IO) {
        val segments = transcript.segments
        if (segments.isEmpty()) {
            throw IOException("Transcript is empty. Complete transcription before AI Analysis.")
        }

        val fullFormattedTranscript = formatTranscript(segments)
        val transcriptBytes = fullFormattedTranscript.toByteArray(Charsets.UTF_8).size

        val totalDurationMs = if (videoDurationMs > 0L) {
            videoDurationMs
        } else {
            segments.maxOfOrNull { it.endMs } ?: 0L
        }

        val allCandidates = mutableListOf<ClipCandidate>()

        if (transcriptBytes <= MAX_TRANSCRIPT_BYTES_SINGLE_CALL && totalDurationMs <= WINDOW_DURATION_MS) {
            // Single call path
            onProgress("Analyzing transcript with Gemini Flash...")
            val systemInstruction = buildSystemPrompt(requestedClipCount, minLengthSeconds)
            val userPrompt = buildUserPrompt(fullFormattedTranscript, totalDurationMs)

            val jsonText = geminiClient.generateContentWithRetry(systemInstruction, userPrompt)
            val clips = parseAndValidateClips(jsonText, totalDurationMs, minLengthSeconds)
            allCandidates.addAll(clips)
        } else {
            // Long transcript chunking path: sequential 30-min windows with 2-min overlap
            val stepMs = WINDOW_DURATION_MS - OVERLAP_DURATION_MS
            val windowRanges = mutableListOf<Pair<Long, Long>>()
            var start = 0L
            while (start < totalDurationMs) {
                val end = minOf(start + WINDOW_DURATION_MS, totalDurationMs)
                windowRanges.add(start to end)
                if (end >= totalDurationMs) break
                start += stepMs
            }

            val totalParts = windowRanges.size
            val clipsPerPart = maxOf(2, (requestedClipCount / totalParts) + 2)

            for ((index, range) in windowRanges.withIndex()) {
                val partNum = index + 1
                onProgress("Analyzing part $partNum of $totalParts...")

                val windowSegments = segments.filter { seg ->
                    seg.startMs < range.second && seg.endMs > range.first
                }

                if (windowSegments.isEmpty()) continue

                val windowTranscript = formatTranscript(windowSegments)
                val systemInstruction = buildSystemPrompt(clipsPerPart, minLengthSeconds)
                val userPrompt = buildUserPrompt(windowTranscript, totalDurationMs)

                try {
                    val jsonText = geminiClient.generateContentWithRetry(systemInstruction, userPrompt)
                    val clips = parseAndValidateClips(jsonText, totalDurationMs, minLengthSeconds)
                    allCandidates.addAll(clips)
                } catch (e: Exception) {
                    // If a single part fails after retries, continue with remaining parts if some exist
                    if (totalParts == 1) throw e
                }
            }
        }

        // Deduplicate overlapping candidates (keep higher score) and sort descending by score
        val dedupedClips = deduplicateAndRankClips(allCandidates, requestedClipCount)

        // Save clips to disk cache: projects/{projectId}/clips/clips.json
        saveClipsToDisk(projectId, dedupedClips)

        val validCount = dedupedClips.size
        val statusMessage = "Found $validCount of $requestedClipCount requested clips"

        DiscoveryResult(
            clips = dedupedClips,
            requestedCount = requestedClipCount,
            validCount = validCount,
            statusMessage = statusMessage
        )
    }

    /**
     * Converts transcript segments into compact format: "[MM:SS] Speaker 1: text".
     */
    fun formatTranscript(segments: List<TranscriptSegment>): String {
        val sb = StringBuilder()
        for (seg in segments) {
            val time = formatMsToTimestamp(seg.startMs)
            val speaker = seg.speakerLabel?.takeIf { it.isNotBlank() } ?: "Speaker 1"
            sb.append("[$time] $speaker: ${seg.text.trim()}\n")
        }
        return sb.toString()
    }

    fun buildSystemPrompt(requestedCount: Int, minLengthSeconds: Int): String {
        return SCORING_PROMPT_TEMPLATE
            .replace("{N}", requestedCount.toString())
            .replace("{MIN_LEN}", minLengthSeconds.toString())
    }

    private fun buildUserPrompt(formattedTranscript: String, totalDurationMs: Long): String {
        val durationStr = formatMsToTimestamp(totalDurationMs)
        return "VIDEO TOTAL DURATION: $durationStr\n\nTRANSCRIPT WITH TIMESTAMPS:\n$formattedTranscript"
    }

    /**
     * Parses the strict JSON response and validates each clip candidate.
     */
    fun parseAndValidateClips(
        jsonString: String,
        videoDurationMs: Long,
        minLengthSeconds: Int
    ): List<ClipCandidate> {
        val validClips = mutableListOf<ClipCandidate>()

        val root = try {
            JSONObject(jsonString)
        } catch (e: Exception) {
            throw IOException("Gemini returned an unusable answer. Tap Retry.")
        }

        val clipsArray = root.optJSONArray("clips")
            ?: throw IOException("Gemini returned an unusable answer. Tap Retry.")

        for (i in 0 until clipsArray.length()) {
            val clipObj = clipsArray.optJSONObject(i) ?: continue

            val rawScore = clipObj.optDouble("score", -1.0)
            val score = if (rawScore in 0.0..100.0) rawScore.toFloat() else continue

            val title = clipObj.optString("title", "").trim()
            if (title.isBlank()) continue

            val hookSentence = clipObj.optString("hookSentence", "").trim()
            val viralityReason = clipObj.optString("viralityReason", "").trim()
            val caption = clipObj.optString("caption", "").trim()
            val transcriptText = clipObj.optString("transcriptText", "").trim()

            // Parse hashtags
            val hashtags = mutableListOf<String>()
            val tagsArr = clipObj.optJSONArray("hashtags")
            if (tagsArr != null) {
                for (t in 0 until tagsArr.length()) {
                    val tag = tagsArr.optString(t, "").trim()
                    if (tag.isNotBlank()) hashtags.add(tag)
                }
            }

            // Parse sourceRanges
            val sourceRanges = mutableListOf<SourceRange>()
            val rangesArr = clipObj.optJSONArray("sourceRanges")
            if (rangesArr != null && rangesArr.length() > 0) {
                for (r in 0 until rangesArr.length()) {
                    val rObj = rangesArr.optJSONObject(r) ?: continue
                    val startStr = rObj.optString("start", "")
                    val endStr = rObj.optString("end", "")
                    val sMs = parseTimestampToMs(startStr)
                    val eMs = parseTimestampToMs(endStr)

                    if (sMs != null && eMs != null && eMs > sMs) {
                        // Validate range against video duration
                        if (videoDurationMs > 0L && sMs >= videoDurationMs) {
                            continue
                        }
                        val clampedEnd = if (videoDurationMs > 0L && eMs > videoDurationMs) {
                            videoDurationMs
                        } else {
                            eMs
                        }
                        if (clampedEnd > sMs) {
                            sourceRanges.add(SourceRange(sMs, clampedEnd, rawStart = startStr, rawEnd = endStr))
                        }
                    }
                }
            }

            // Must have at least one valid source range
            if (sourceRanges.isEmpty()) continue

            // Parse hookStart and hookEnd
            val hookStartStr = clipObj.optString("hookStart", "")
            val hookEndStr = clipObj.optString("hookEnd", "")
            val hookStartMs = parseTimestampToMs(hookStartStr) ?: sourceRanges.first().startMs
            val hookEndMs = parseTimestampToMs(hookEndStr) ?: minOf(hookStartMs + 3000L, sourceRanges.first().endMs)

            val candidate = ClipCandidate(
                clipId = clipObj.optString("clipId", UUID.randomUUID().toString()),
                sourceRanges = sourceRanges,
                hookStartMs = hookStartMs,
                hookEndMs = hookEndMs,
                rawHookStart = hookStartStr.takeIf { it.isNotBlank() },
                rawHookEnd = hookEndStr.takeIf { it.isNotBlank() },
                transcriptText = transcriptText,
                score = score,
                title = title,
                hookSentence = hookSentence,
                viralityReason = viralityReason,
                caption = caption,
                hashtags = hashtags,
                isIncludedInExport = true
            )
            validClips.add(candidate)
        }

        return validClips
    }

    /**
     * Dedupes overlapping clips by keeping the one with higher score,
     * and sorts descending by score.
     */
    fun deduplicateAndRankClips(
        clips: List<ClipCandidate>,
        maxCount: Int
    ): List<ClipCandidate> {
        val sorted = clips.sortedByDescending { it.score }
        val result = mutableListOf<ClipCandidate>()

        for (candidate in sorted) {
            val overlaps = result.any { existing ->
                areClipsOverlapping(existing, candidate)
            }
            if (!overlaps) {
                result.add(candidate)
            }
            if (result.size >= maxCount) break
        }

        return result
    }

    private fun areClipsOverlapping(c1: ClipCandidate, c2: ClipCandidate): Boolean {
        val r1 = c1.sourceRanges.firstOrNull() ?: return false
        val r2 = c2.sourceRanges.firstOrNull() ?: return false

        val overlapStart = maxOf(r1.startMs, r2.startMs)
        val overlapEnd = minOf(r1.endMs, r2.endMs)
        val overlapDuration = maxOf(0L, overlapEnd - overlapStart)

        val d1 = r1.endMs - r1.startMs
        val d2 = r2.endMs - r2.startMs
        val minDuration = minOf(d1, d2)

        if (minDuration <= 0L) return false

        // Consider overlapping if overlap is 50% or more of the smaller clip
        return (overlapDuration.toFloat() / minDuration.toFloat()) >= 0.5f
    }

    fun parseTimestampToMs(timeStr: String): Long? {
        if (timeStr.isBlank()) return null
        val clean = timeStr.trim().removePrefix("[").removeSuffix("]")
        val parts = clean.split(":")
        return when (parts.size) {
            2 -> {
                val min = parts[0].toLongOrNull() ?: return null
                val secParts = parts[1].split(".")
                val sec = secParts[0].toLongOrNull() ?: return null
                val ms = if (secParts.size > 1) {
                    secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                } else 0L
                (min * 60 + sec) * 1000L + ms
            }
            3 -> {
                val hr = parts[0].toLongOrNull() ?: return null
                val min = parts[1].toLongOrNull() ?: return null
                val secParts = parts[2].split(".")
                val sec = secParts[0].toLongOrNull() ?: return null
                val ms = if (secParts.size > 1) {
                    secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
                } else 0L
                (hr * 3600 + min * 60 + sec) * 1000L + ms
            }
            else -> null
        }
    }

    fun formatMsToTimestamp(ms: Long): String {
        val totalSec = ms / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun saveClipsToDisk(projectId: String, clips: List<ClipCandidate>) {
        try {
            val clipsDir = File(context.filesDir, "projects/$projectId/clips")
            clipsDir.mkdirs()
            val clipsFile = File(clipsDir, "clips.json")
            val converters = ProjectTypeConverters()
            val json = converters.fromClipCandidates(clips)
            if (json != null) {
                clipsFile.writeText(json)
            }
        } catch (e: Exception) {
            // Non-critical file write failure
        }
    }

    fun loadClipsFromDisk(projectId: String): List<ClipCandidate> {
        return try {
            val clipsFile = File(context.filesDir, "projects/$projectId/clips/clips.json")
            if (clipsFile.exists()) {
                val converters = ProjectTypeConverters()
                converters.toClipCandidates(clipsFile.readText())
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
