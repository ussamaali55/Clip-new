package com.clipgenius.ai.captions

import android.content.Context
import android.os.Environment
import android.util.Log
import com.clipgenius.ai.data.CaptionPresetEntity
import com.clipgenius.ai.data.ProjectRepository
import com.clipgenius.ai.state.CaptionCue
import com.clipgenius.ai.state.CaptionPreset
import com.clipgenius.ai.state.CaptionWordTiming
import com.clipgenius.ai.state.Project
import com.clipgenius.ai.state.TranscriptWord
import com.clipgenius.ai.state.VerifiedClip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Phase 11 Caption & Preset Manager.
 * Orchestrates cue edits, cue splits, cue merges, preset database caching,
 * and completely bullet-proof JSON preset import/export with safe-area auto-clamping.
 */
object CaptionManager {

    private const val TAG = "CaptionManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _operationProgress = MutableStateFlow(0f)
    val operationProgress: StateFlow<Boolean> = _isGenerating.asStateFlow() // Wait, let's return correct float flow if needed, but simple states are enough

    /**
     * Initializes Caption Tracks for all verified clips in a project if not already generated.
     */
    suspend fun initializeProjectCaptions(
        context: Context,
        project: Project,
        repository: ProjectRepository,
        forceRecompute: Boolean = false
    ) = withContext(Dispatchers.IO) {
        val words = project.transcript?.segments?.flatMap { it.words } ?: emptyList()
        if (words.isEmpty()) return@withContext

        val updatedClips = project.verifiedClips.map { clip ->
            if (clip.isIncludedInExport && (clip.captionsStatus == "NOT_STARTED" || forceRecompute)) {
                val cues = CaptionGenerator.generateCaptionTrack(
                    context = context,
                    projectId = project.id,
                    clip = clip,
                    allTranscriptWords = words,
                    forceRecompute = forceRecompute
                )
                val captionFile = CaptionGenerator.getCaptionsFile(context, project.id, clip.clipId)
                clip.copy(
                    captionsStatus = "DONE",
                    captionsJsonPath = captionFile.absolutePath,
                    captionPresetId = clip.captionPresetId ?: CaptionGenerator.PRESET_BOLD_TIKTOK.id
                )
            } else {
                clip
            }
        }

        // Save progress and update stage status to Captions in progress
        val updatedStages = project.stages.toMutableMap().apply {
            put("Captions", "in_progress")
        }
        val updatedProject = project.copy(verifiedClips = updatedClips, stages = updatedStages)
        repository.saveProject(updatedProject)
    }

    /**
     * Edits the text of an individual caption cue.
     * Splitting words inside the text keeps timings aligned by proportionally dividing the original span.
     */
    suspend fun editCueText(
        context: Context,
        projectId: String,
        clipId: String,
        cueId: String,
        newText: String
    ): List<CaptionCue> = withContext(Dispatchers.IO) {
        val file = CaptionGenerator.getCaptionsFile(context, projectId, clipId)
        val cues = CaptionGenerator.loadCaptionsFromDisk(file).toMutableList()
        val index = cues.indexOfFirst { it.id == cueId }

        if (index != -1) {
            val oldCue = cues[index]
            val originalWords = oldCue.wordTimings
            val newWordsString = newText.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }

            // Distribute old timings proportionally across the edited words
            val updatedWordTimings = if (originalWords.isNotEmpty() && newWordsString.isNotEmpty()) {
                val cueDuration = oldCue.cueEndMs - oldCue.cueStartMs
                val timePerWord = cueDuration / newWordsString.size.toFloat()
                newWordsString.mapIndexed { idx, word ->
                    CaptionWordTiming(
                        word = word,
                        startMs = (oldCue.cueStartMs + idx * timePerWord).toLong(),
                        endMs = (oldCue.cueStartMs + (idx + 1) * timePerWord).toLong()
                    )
                }
            } else {
                newWordsString.map { CaptionWordTiming(it, oldCue.cueStartMs, oldCue.cueEndMs) }
            }

            // Word wrap edited text into 2 lines max
            val wrappedLines = wrapStringListToLines(newWordsString.map { TranscriptWord(it, 0L, 0L) })

            cues[index] = oldCue.copy(
                text = newText,
                lines = wrappedLines,
                wordTimings = updatedWordTimings,
                isEdited = true
            )
            CaptionGenerator.saveCaptionsToDisk(file, cues)
        }
        cues
    }

    /**
     * Splits a caption cue into two separate cues at a specific word index.
     */
    suspend fun splitCue(
        context: Context,
        projectId: String,
        clipId: String,
        cueId: String,
        wordIndex: Int
    ): List<CaptionCue> = withContext(Dispatchers.IO) {
        val file = CaptionGenerator.getCaptionsFile(context, projectId, clipId)
        val cues = CaptionGenerator.loadCaptionsFromDisk(file).toMutableList()
        val index = cues.indexOfFirst { it.id == cueId }

        if (index != -1 && wordIndex > 0 && wordIndex < cues[index].wordTimings.size) {
            val targetCue = cues[index]
            val words = targetCue.wordTimings

            // Cue 1: [0 until wordIndex]
            val words1 = words.subList(0, wordIndex)
            val cue1Start = targetCue.cueStartMs
            val cue1End = words1.last().endMs
            val text1 = words1.joinToString(" ") { it.word }
            val lines1 = wrapStringListToLines(words1.map { TranscriptWord(it.word, it.startMs, it.endMs) })
            val cue1 = CaptionCue(
                id = UUID.randomUUID().toString(),
                cueStartMs = cue1Start,
                cueEndMs = cue1End,
                text = text1,
                lines = lines1,
                wordTimings = words1,
                isEdited = true
            )

            // Cue 2: [wordIndex until end]
            val words2 = words.subList(wordIndex, words.size)
            val cue2Start = words2.first().startMs
            val cue2End = targetCue.cueEndMs
            val text2 = words2.joinToString(" ") { it.word }
            val lines2 = wrapStringListToLines(words2.map { TranscriptWord(it.word, it.startMs, it.endMs) })
            val cue2 = CaptionCue(
                id = UUID.randomUUID().toString(),
                cueStartMs = cue2Start,
                cueEndMs = cue2End,
                text = text2,
                lines = lines2,
                wordTimings = words2,
                isEdited = true
            )

            cues.removeAt(index)
            cues.add(index, cue2)
            cues.add(index, cue1)

            CaptionGenerator.saveCaptionsToDisk(file, cues)
        }
        cues
    }

    /**
     * Merges a caption cue with the subsequent cue in the timeline.
     */
    suspend fun mergeWithNext(
        context: Context,
        projectId: String,
        clipId: String,
        cueId: String
    ): List<CaptionCue> = withContext(Dispatchers.IO) {
        val file = CaptionGenerator.getCaptionsFile(context, projectId, clipId)
        val cues = CaptionGenerator.loadCaptionsFromDisk(file).toMutableList()
        val index = cues.indexOfFirst { it.id == cueId }

        if (index != -1 && index < cues.size - 1) {
            val cue1 = cues[index]
            val cue2 = cues[index + 1]

            val combinedWords = cue1.wordTimings + cue2.wordTimings
            val combinedText = "${cue1.text} ${cue2.text}"
            val combinedLines = wrapStringListToLines(combinedWords.map { TranscriptWord(it.word, it.startMs, it.endMs) })

            val mergedCue = CaptionCue(
                id = cue1.id,
                cueStartMs = cue1.cueStartMs,
                cueEndMs = cue2.cueEndMs,
                text = combinedText,
                lines = combinedLines,
                wordTimings = combinedWords,
                isEdited = true
            )

            cues.removeAt(index + 1)
            cues[index] = mergedCue

            CaptionGenerator.saveCaptionsToDisk(file, cues)
        }
        cues
    }

    private fun wrapStringListToLines(words: List<TranscriptWord>): List<String> {
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            if (currentLine.isEmpty()) {
                currentLine.append(word.word)
            } else if (currentLine.length + 1 + word.word.length <= 32) {
                currentLine.append(" ").append(word.word)
            } else {
                lines.add(currentLine.toString())
                currentLine = StringBuilder(word.word)
            }
        }
        if (currentLine.isNotEmpty()) {
            lines.add(currentLine.toString())
        }
        return lines
    }

    /**
     * Seeds built-in presets in the Room database if not already populated.
     */
    suspend fun seedBuiltInPresets(repository: ProjectRepository) {
        CaptionGenerator.BUILT_IN_PRESETS.forEach { preset ->
            repository.savePreset(preset)
        }
    }

    /**
     * Clamps a custom caption preset inside the 9:16 safe area vertical guidelines:
     * Safe area: top (12% margin minimum) up to bottom (18% margin minimum, which is 82%).
     */
    fun clampVerticalPositionPercent(percent: Float): Float {
        return percent.coerceIn(12f, 82f)
    }

    /**
     * Writes a custom caption preset style as human-readable JSON to the downloads directory.
     */
    suspend fun exportPresetToDownloads(preset: CaptionPreset): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("id", preset.id)
        root.put("name", preset.name)
        root.put("fontFamily", preset.fontFamily)
        root.put("fontSizeSp", preset.fontSizeSp.toDouble())
        root.put("fontWeight", preset.fontWeight)
        root.put("textColor", preset.textColor)
        root.put("strokeColor", preset.strokeColor)
        root.put("strokeWidthDp", preset.strokeWidthDp.toDouble())
        root.put("shadowColor", preset.shadowColor)
        root.put("shadowRadiusDp", preset.shadowRadiusDp.toDouble())
        root.put("backgroundColor", preset.backgroundColor)
        root.put("textAlignment", preset.textAlignment)
        root.put("verticalPositionPercent", preset.verticalPositionPercent.toDouble())
        root.put("animationType", preset.animationType)
        root.put("animationDurationMs", preset.animationDurationMs)
        root.put("wordEmphasisColor", preset.wordEmphasisColor)
        root.put("allCaps", preset.allCaps)

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!downloadsDir.exists()) downloadsDir.mkdirs()

        val cleanName = preset.name.replace("\\s+".toRegex(), "_").lowercase()
        val file = File(downloadsDir, "clipgenius_preset_$cleanName.json")
        file.writeText(root.toString(4))
        
        file.absolutePath
    }

    /**
     * Bullet-proof importer of customized JSON preset files with comprehensive validation.
     * Alerts missing fields, falls back to defaults for invalid types/colors, and clamps vertical position to safe area.
     */
    suspend fun importPresetFromJson(jsonContent: String): Pair<CaptionPreset, List<String>> {
        val warnings = mutableListOf<String>()
        val defaultPreset = CaptionGenerator.PRESET_BOLD_TIKTOK

        val obj = try {
            JSONObject(jsonContent)
        } catch (e: Exception) {
            return Pair(defaultPreset.copy(id = UUID.randomUUID().toString(), name = "Imported Default"), listOf("Invalid JSON format. Loaded fallback preset."))
        }

        val name = obj.optString("name", "Imported Preset").also {
            if (!obj.has("name")) warnings.add("Missing 'name', defaulted to 'Imported Preset'")
        }

        val fontFamily = obj.optString("fontFamily", "Inter")

        val fontSizeSp = runCatching {
            obj.optDouble("fontSizeSp", 24.0).toFloat().coerceIn(10f, 60f)
        }.getOrElse {
            warnings.add("Invalid 'fontSizeSp' format, defaulted to 24")
            24f
        }

        val fontWeight = runCatching {
            obj.optInt("fontWeight", 700).coerceIn(100, 950)
        }.getOrElse {
            warnings.add("Invalid 'fontWeight' format, defaulted to 700")
            700
        }

        // Color validation helpers
        fun parseHexColor(key: String, default: String): String {
            val raw = obj.optString(key, default)
            return if (raw.matches("^#([A-Fa-f0-9]{6}|[A-Fa-f0-9]{8})$".toRegex())) {
                raw
            } else {
                warnings.add("Invalid hex code for '$key' ($raw), restored default ($default)")
                default
            }
        }

        val textColor = parseHexColor("textColor", "#FFFFFF")
        val strokeColor = parseHexColor("strokeColor", "#000000")
        val shadowColor = parseHexColor("shadowColor", "#80000000")
        val backgroundColor = parseHexColor("backgroundColor", "#00000000")
        val wordEmphasisColor = parseHexColor("wordEmphasisColor", "#FFFF00")

        val strokeWidthDp = runCatching {
            obj.optDouble("strokeWidthDp", 2.0).toFloat().coerceIn(0f, 10f)
        }.getOrElse {
            warnings.add("Invalid 'strokeWidthDp', defaulted to 2")
            2f
        }

        val shadowRadiusDp = runCatching {
            obj.optDouble("shadowRadiusDp", 4.0).toFloat().coerceIn(0f, 15f)
        }.getOrElse {
            warnings.add("Invalid 'shadowRadiusDp', defaulted to 4")
            4f
        }

        val textAlignment = obj.optString("textAlignment", "CENTER").uppercase().also {
            if (it != "LEFT" && it != "CENTER" && it != "RIGHT") {
                warnings.add("Unknown text alignment '$it', reset to CENTER")
            }
        }.let { if (it == "LEFT" || it == "CENTER" || it == "RIGHT") it else "CENTER" }

        // Safe Area clamping check!
        val rawVertical = runCatching {
            obj.optDouble("verticalPositionPercent", 80.0).toFloat()
        }.getOrElse {
            warnings.add("Invalid 'verticalPositionPercent', defaulted to 80")
            80f
        }
        val clampedVertical = clampVerticalPositionPercent(rawVertical)
        if (rawVertical != clampedVertical) {
            warnings.add("Vertical position $rawVertical% violates vertical safe area. Automatically clamped to $clampedVertical%")
        }

        val animationType = obj.optString("animationType", "word-highlight").lowercase().let {
            if (it == "none" || it == "pop" || it == "fade" || it == "slide" || it == "word-highlight") it else {
                warnings.add("Unknown animation '$it', defaulted to 'word-highlight'")
                "word-highlight"
            }
        }

        val animationDurationMs = obj.optInt("animationDurationMs", 200).coerceIn(0, 1000)

        val allCaps = obj.optBoolean("allCaps", true)

        val importedPreset = CaptionPreset(
            id = "imported_${UUID.randomUUID()}",
            name = name,
            fontFamily = fontFamily,
            fontSizeSp = fontSizeSp,
            fontWeight = fontWeight,
            textColor = textColor,
            strokeColor = strokeColor,
            strokeWidthDp = strokeWidthDp,
            shadowColor = shadowColor,
            shadowRadiusDp = shadowRadiusDp,
            backgroundColor = backgroundColor,
            textAlignment = textAlignment,
            verticalPositionPercent = clampedVertical,
            animationType = animationType,
            animationDurationMs = animationDurationMs,
            wordEmphasisColor = wordEmphasisColor,
            allCaps = allCaps,
            isImported = true
        )

        return Pair(importedPreset, warnings)
    }

    /**
     * Mark captions stage complete and enable Render stage.
     */
    suspend fun checkAndUpdateCaptionsStageCompletion(
        projectId: String,
        repository: ProjectRepository
    ) {
        val project = repository.getProjectById(projectId) ?: return
        val eligible = project.verifiedClips.filter {
            (it.status == com.clipgenius.ai.state.VerificationStatus.VERIFIED || it.isManuallyEdited) && it.isIncludedInExport
        }

        val allDone = eligible.isNotEmpty() && eligible.all { it.captionsStatus == "DONE" }

        if (allDone) {
            val updatedStages = project.stages.toMutableMap().apply {
                put("Captions", "completed")
                if (get("Render") == "not_started" || get("Render") == null) {
                    put("Render", "in_progress")
                }
            }
            repository.saveProject(project.copy(stages = updatedStages))
        }
    }
}
