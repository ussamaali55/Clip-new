package com.clipgenius.ai.render

import com.clipgenius.ai.state.CaptionCue
import com.clipgenius.ai.state.CaptionPreset
import java.io.File
import java.util.Locale

/**
 * Phase 12 Substation Alpha (.ass) Subtitle Generator for FFmpeg libass burning.
 * Converts Phase 11 CaptionCue word timings & CaptionPreset styles into high-fidelity ASS script.
 */
object SubtitleAssGenerator {

    /**
     * Converts a Hex color (#RRGGBB or #AARRGGBB) to ASS color format (&HAABBGGRR).
     */
    fun hexToAssColor(hex: String, defaultAlpha: String = "00"): String {
        val clean = hex.trim().removePrefix("#")
        return try {
            when (clean.length) {
                6 -> {
                    val r = clean.substring(0, 2)
                    val g = clean.substring(2, 4)
                    val b = clean.substring(4, 6)
                    "&H${defaultAlpha}${b}${g}${r}".uppercase(Locale.US)
                }
                8 -> {
                    val a = clean.substring(0, 2)
                    val r = clean.substring(2, 4)
                    val g = clean.substring(4, 6)
                    val b = clean.substring(6, 8)
                    // ASS alpha: 00 is opaque, FF is transparent
                    "&H${a}${b}${g}${r}".uppercase(Locale.US)
                }
                else -> "&H00FFFFFF"
            }
        } catch (e: Exception) {
            "&H00FFFFFF"
        }
    }

    /**
     * Formats milliseconds into ASS timestamp format: H:MM:SS.cs (centiseconds)
     */
    fun formatAssTime(ms: Long): String {
        val totalCentis = (ms / 10).coerceAtLeast(0)
        val cs = totalCentis % 100
        val totalSecs = totalCentis / 100
        val s = totalSecs % 60
        val totalMins = totalSecs / 60
        val m = totalMins % 60
        val h = totalMins / 60
        return String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
    }

    /**
     * Generates a complete .ass file with styling derived from CaptionPreset.
     */
    fun generateAssFile(
        outputFile: File,
        cues: List<CaptionCue>,
        preset: CaptionPreset
    ): File {
        val primaryColor = hexToAssColor(preset.textColor)
        val outlineColor = hexToAssColor(preset.strokeColor)
        val backColor = hexToAssColor(preset.shadowColor, "80")

        val boldFlag = if (preset.fontWeight >= 700) "-1" else "0"
        val outlineWidth = preset.strokeWidthDp.coerceAtLeast(0f)
        val shadowDepth = preset.shadowRadiusDp.coerceAtLeast(0f)

        // Alignment: 2 = Bottom Center, 5 = Middle Center, 8 = Top Center
        val alignment = when {
            preset.verticalPositionPercent <= 30f -> 8
            preset.verticalPositionPercent in 31f..65f -> 5
            else -> 2
        }

        // MarginV based on safe area percent (out of 1920)
        val marginV = ((preset.verticalPositionPercent / 100f) * 1920).toInt().coerceIn(240, 1600)
        val effectiveMarginV = if (alignment == 2) {
            (1920 - marginV).coerceIn(150, 600)
        } else if (alignment == 8) {
            marginV.coerceIn(150, 600)
        } else {
            0
        }

        val fontName = when (preset.fontFamily) {
            "serif" -> "DejaVu Serif"
            "monospace" -> "DejaVu Sans Mono"
            else -> "Arial"
        }
        val fontSize = (preset.fontSizeSp * 2.2f).toInt().coerceIn(36, 110)

        val sb = StringBuilder()
        sb.appendLine("[Script Info]")
        sb.appendLine("Title: ClipGenius Subtitles")
        sb.appendLine("ScriptType: v4.00+")
        sb.appendLine("WrapStyle: 0")
        sb.appendLine("ScaledBorderAndShadow: yes")
        sb.appendLine("PlayResX: 1080")
        sb.appendLine("PlayResY: 1920")
        sb.appendLine()
        sb.appendLine("[V4+ Styles]")
        sb.appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        sb.appendLine(
            "Style: Default,$fontName,$fontSize,$primaryColor,&H0000FFFF,$outlineColor,$backColor,$boldFlag,0,0,0,100,100,0,0,1,$outlineWidth,$shadowDepth,$alignment,40,40,$effectiveMarginV,1"
        )
        sb.appendLine()
        sb.appendLine("[Events]")
        sb.appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")

        for (cue in cues) {
            val start = formatAssTime(cue.cueStartMs)
            val end = formatAssTime(cue.cueEndMs)

            // Construct text with lines joined by \N
            val linesToUse = if (cue.lines.isNotEmpty()) cue.lines else listOf(cue.text)
            val formattedLines = linesToUse.map { line ->
                val processed = if (preset.allCaps) line.uppercase(Locale.US) else line
                processed.replace("{", "\\{").replace("}", "\\}")
            }
            val assText = formattedLines.joinToString("\\N")

            sb.appendLine("Dialogue: 0,$start,$end,Default,,0,0,0,,$assText")
        }

        outputFile.parentFile?.mkdirs()
        outputFile.writeText(sb.toString())
        return outputFile
    }
}
