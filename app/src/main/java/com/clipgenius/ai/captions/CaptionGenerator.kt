package com.clipgenius.ai.captions

import android.content.Context
import android.util.Log
import com.clipgenius.ai.state.CaptionCue
import com.clipgenius.ai.state.CaptionPreset
import com.clipgenius.ai.state.CaptionWordTiming
import com.clipgenius.ai.state.VerifiedClip
import com.clipgenius.ai.state.TranscriptWord
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Phase 11 Caption Track Generator.
 * 1. Groups word-level timestamps from transcript into readable 1-4s caption cues.
 * 2. Employs a text-layout word wrapping algorithm (max 2 lines, max 32 chars/line).
 * 3. Builds and ships the 4 built-in premium caption presets.
 * 4. Saves/loads generated captions from filesDir/projects/{projectId}/captions/{clipId}_captions.json.
 */
object CaptionGenerator {

    private const val TAG = "CaptionGenerator"
    private const val MAX_CHARS_PER_LINE = 32
    private const val MIN_CUE_DURATION_MS = 1000L
    private const val MAX_CUE_DURATION_MS = 4000L

    // --- 4 BUILT-IN PREMIUM PRESETS ---
    val PRESET_BOLD_TIKTOK = CaptionPreset(
        id = "preset_bold_tiktok",
        name = "Bold TikTok",
        fontFamily = "sans-serif-black",
        fontSizeSp = 28f,
        fontWeight = 900,
        textColor = "#FFFFFF",
        strokeColor = "#000000",
        strokeWidthDp = 4f,
        shadowColor = "#80000000",
        shadowRadiusDp = 5f,
        backgroundColor = "#E0000000",
        textAlignment = "CENTER",
        verticalPositionPercent = 65f, // center/mid-bottom
        animationType = "word-highlight",
        animationDurationMs = 150,
        wordEmphasisColor = "#FFFF00", // Yellow highlight
        allCaps = true,
        isImported = false
    )

    val PRESET_MINIMAL = CaptionPreset(
        id = "preset_minimal",
        name = "Minimal",
        fontFamily = "sans-serif",
        fontSizeSp = 20f,
        fontWeight = 400,
        textColor = "#FFFFFF",
        strokeColor = "#00000000",
        strokeWidthDp = 0f,
        shadowColor = "#80000000",
        shadowRadiusDp = 3f,
        backgroundColor = "#00000000",
        textAlignment = "CENTER",
        verticalPositionPercent = 82f, // Bottom safe area
        animationType = "none",
        animationDurationMs = 0,
        wordEmphasisColor = "#FFFFFF",
        allCaps = false,
        isImported = false
    )

    val PRESET_NEON_POP = CaptionPreset(
        id = "preset_neon_pop",
        name = "Neon Pop",
        fontFamily = "monospace",
        fontSizeSp = 26f,
        fontWeight = 700,
        textColor = "#00FFCC", // Neon cyan
        strokeColor = "#FF007F", // Neon pink
        strokeWidthDp = 3f,
        shadowColor = "#C0FF007F",
        shadowRadiusDp = 6f,
        backgroundColor = "#00000000",
        textAlignment = "CENTER",
        verticalPositionPercent = 50f, // Center
        animationType = "pop",
        animationDurationMs = 250,
        wordEmphasisColor = "#FFFF00",
        allCaps = true,
        isImported = false
    )

    val PRESET_CINEMA = CaptionPreset(
        id = "preset_cinema",
        name = "Cinema",
        fontFamily = "serif",
        fontSizeSp = 22f,
        fontWeight = 400,
        textColor = "#F5F5F5",
        strokeColor = "#A0000000",
        strokeWidthDp = 1.5f,
        shadowColor = "#00000000",
        shadowRadiusDp = 0f,
        backgroundColor = "#CC0A0A0A", // Cinematic translucent box
        textAlignment = "CENTER",
        verticalPositionPercent = 85f,
        animationType = "fade",
        animationDurationMs = 300,
        wordEmphasisColor = "#F5F5F5",
        allCaps = false,
        isImported = false
    )

    val BUILT_IN_PRESETS = listOf(
        PRESET_BOLD_TIKTOK,
        PRESET_MINIMAL,
        PRESET_NEON_POP,
        PRESET_CINEMA
    )

    fun getCaptionsFile(context: Context, projectId: String, clipId: String): File {
        val dir = File(context.filesDir, "projects/$projectId/captions")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "${clipId}_captions.json")
    }

    /**
     * Groups word-level transcript timestamps into caption cues for a clip.
     */
    fun generateCaptionTrack(
        context: Context,
        projectId: String,
        clip: VerifiedClip,
        allTranscriptWords: List<TranscriptWord>,
        forceRecompute: Boolean = false
    ): List<CaptionCue> {
        val captionsFile = getCaptionsFile(context, projectId, clip.clipId)

        if (!forceRecompute && captionsFile.exists() && captionsFile.length() > 0) {
            val cached = loadCaptionsFromDisk(captionsFile)
            if (cached.isNotEmpty()) return cached
        }

        val range = clip.verifiedRanges.firstOrNull() ?: return emptyList()
        val clipStartMs = range.startMs
        val clipEndMs = range.endMs

        // Filter words that fall within the clip boundary and offset timings to be relative to clip start (0)
        val clipWords = allTranscriptWords.filter {
            it.startMs >= clipStartMs && it.endMs <= clipEndMs
        }.map {
            it.copy(
                startMs = maxOf(0L, it.startMs - clipStartMs),
                endMs = maxOf(0L, it.endMs - clipStartMs)
            )
        }.sortedBy { it.startMs }

        if (clipWords.isEmpty()) return emptyList()

        val cues = mutableListOf<CaptionCue>()
        var currentWordsList = mutableListOf<TranscriptWord>()

        for (word in clipWords) {
            currentWordsList.add(word)

            val currentCueStart = currentWordsList.first().startMs
            val currentCueEnd = currentWordsList.last().endMs
            val duration = currentCueEnd - currentCueStart

            // Formulate lines using word wrap to check layout constraints
            val lines = wrapWordsToLines(currentWordsList)

            // Break caption cue if any of these conditions are met:
            // 1. Reached max 2 lines
            // 2. Duration exceeds max cue duration (4 seconds)
            // 3. Current word ends with terminal punctuation (. ? !)
            val endsWithPunctuation = word.word.endsWith(".") || word.word.endsWith("?") || word.word.endsWith("!")
            val isMaxLinesExceeded = lines.size > 2
            val isDurationExceeded = duration >= MAX_CUE_DURATION_MS

            if ((isMaxLinesExceeded || isDurationExceeded || (endsWithPunctuation && duration >= MIN_CUE_DURATION_MS)) && currentWordsList.size > 1) {
                // Remove last word and create cue with previous words
                val lastWord = currentWordsList.removeAt(currentWordsList.size - 1)
                
                val completedCue = buildCue(currentWordsList)
                cues.add(completedCue)

                // Start new cue with the removed word
                currentWordsList = mutableListOf(lastWord)
            }
        }

        if (currentWordsList.isNotEmpty()) {
            cues.add(buildCue(currentWordsList))
        }

        saveCaptionsToDisk(captionsFile, cues)
        return cues
    }

    private fun buildCue(words: List<TranscriptWord>): CaptionCue {
        val cueStart = words.first().startMs
        val cueEnd = words.last().endMs
        val text = words.joinToString(" ") { it.word }
        val lines = wrapWordsToLines(words)

        val wordTimings = words.map {
            CaptionWordTiming(
                word = it.word,
                startMs = it.startMs,
                endMs = it.endMs
            )
        }

        return CaptionCue(
            id = UUID.randomUUID().toString(),
            cueStartMs = cueStart,
            cueEndMs = cueEnd,
            text = text,
            lines = lines,
            wordTimings = wordTimings,
            isEdited = false
        )
    }

    private fun wrapWordsToLines(words: List<TranscriptWord>): List<String> {
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val cleanWord = word.word
            if (currentLine.isEmpty()) {
                currentLine.append(cleanWord)
            } else if (currentLine.length + 1 + cleanWord.length <= MAX_CHARS_PER_LINE) {
                currentLine.append(" ").append(cleanWord)
            } else {
                lines.add(currentLine.toString())
                currentLine = StringBuilder(cleanWord)
            }
        }

        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }

        return lines
    }

    fun saveCaptionsToDisk(file: File, cues: List<CaptionCue>) {
        try {
            val root = JSONArray()
            for (cue in cues) {
                val cueObj = JSONObject()
                cueObj.put("id", cue.id)
                cueObj.put("cueStartMs", cue.cueStartMs)
                cueObj.put("cueEndMs", cue.cueEndMs)
                cueObj.put("text", cue.text)
                cueObj.put("isEdited", cue.isEdited)

                val linesArr = JSONArray()
                for (line in cue.lines) {
                    linesArr.put(line)
                }
                cueObj.put("lines", linesArr)

                val timingsArr = JSONArray()
                for (t in cue.wordTimings) {
                    val tObj = JSONObject()
                    tObj.put("word", t.word)
                    tObj.put("startMs", t.startMs)
                    tObj.put("endMs", t.endMs)
                    timingsArr.put(tObj)
                }
                cueObj.put("wordTimings", timingsArr)

                root.put(cueObj)
            }
            file.writeText(root.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save captions to disk", e)
        }
    }

    fun loadCaptionsFromDisk(file: File): List<CaptionCue> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val root = JSONArray(file.readText())
            val cues = mutableListOf<CaptionCue>()
            for (i in 0 until root.length()) {
                val cueObj = root.getJSONObject(i)
                val id = cueObj.optString("id", UUID.randomUUID().toString())
                val cueStartMs = cueObj.getLong("cueStartMs")
                val cueEndMs = cueObj.getLong("cueEndMs")
                val text = cueObj.getString("text")
                val isEdited = cueObj.optBoolean("isEdited", false)

                val linesArr = cueObj.getJSONArray("lines")
                val lines = mutableListOf<String>()
                for (j in 0 until linesArr.length()) {
                    lines.add(linesArr.getString(j))
                }

                val timingsArr = cueObj.getJSONArray("wordTimings")
                val wordTimings = mutableListOf<CaptionWordTiming>()
                for (j in 0 until timingsArr.length()) {
                    val tObj = timingsArr.getJSONObject(j)
                    wordTimings.add(
                        CaptionWordTiming(
                            word = tObj.getString("word"),
                            startMs = tObj.getLong("startMs"),
                            endMs = tObj.getLong("endMs")
                        )
                    )
                }

                cues.add(
                    CaptionCue(
                        id = id,
                        cueStartMs = cueStartMs,
                        cueEndMs = cueEndMs,
                        text = text,
                        lines = lines,
                        wordTimings = wordTimings,
                        isEdited = isEdited
                    )
                )
            }
            cues
        }.getOrDefault(emptyList())
    }
}
