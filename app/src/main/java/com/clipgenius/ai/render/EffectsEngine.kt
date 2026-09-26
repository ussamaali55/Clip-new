package com.clipgenius.ai.render

import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix

/**
 * Phase 12 Video Effects Engine:
 * - 6 Predefined visual looks (None, Warm, Cool, High Contrast, Vintage, Black & White)
 * - Mirroring (Horizontal flip)
 * - Manual reframe crop position and zoom factor (1.0x - 1.5x)
 * - Live Compose color matrix filters for instant zero-latency preview
 */
object EffectsEngine {

    val FILTER_NAMES = listOf(
        "None",
        "Warm",
        "Cool",
        "High Contrast",
        "Vintage",
        "Black & White"
    )

    /**
     * Builds the FFmpeg video filter component string for the selected filter look.
     */
    fun getFfmpegFilterString(filterName: String): String {
        return when (filterName) {
            "Warm" -> "colorbalance=rs=0.08:gs=0.03:bs=-0.08,eq=saturation=1.12"
            "Cool" -> "colorbalance=rs=-0.08:gs=0.0:bs=0.12,eq=saturation=1.05"
            "High Contrast" -> "eq=contrast=1.3:saturation=1.2:brightness=-0.02"
            "Vintage" -> "colorbalance=rs=0.1:gs=0.04:bs=-0.08,eq=contrast=1.15:saturation=0.85"
            "Black & White" -> "hue=s=0"
            else -> ""
        }
    }

    /**
     * Returns a Compose ColorFilter matching the filter look for real-time live preview.
     */
    fun getComposeColorFilter(filterName: String): ColorFilter? {
        return when (filterName) {
            "Warm" -> {
                val matrix = ColorMatrix(
                    floatArrayOf(
                        1.15f, 0.0f, 0.0f, 0.0f, 10f,
                        0.0f, 1.05f, 0.0f, 0.0f, 5f,
                        0.0f, 0.0f, 0.88f, 0.0f, -10f,
                        0.0f, 0.0f, 0.0f, 1.0f, 0f
                    )
                )
                ColorFilter.colorMatrix(matrix)
            }
            "Cool" -> {
                val matrix = ColorMatrix(
                    floatArrayOf(
                        0.92f, 0.0f, 0.0f, 0.0f, -5f,
                        0.0f, 1.0f, 0.0f, 0.0f, 0f,
                        0.0f, 0.0f, 1.18f, 0.0f, 15f,
                        0.0f, 0.0f, 0.0f, 1.0f, 0f
                    )
                )
                ColorFilter.colorMatrix(matrix)
            }
            "High Contrast" -> {
                val c = 1.35f
                val t = (1.0f - c) / 2.0f * 255.0f
                val matrix = ColorMatrix(
                    floatArrayOf(
                        c, 0f, 0f, 0f, t,
                        0f, c, 0f, 0f, t,
                        0f, 0f, c, 0f, t,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                ColorFilter.colorMatrix(matrix)
            }
            "Vintage" -> {
                val matrix = ColorMatrix(
                    floatArrayOf(
                        0.393f * 1.1f, 0.769f * 0.9f, 0.189f * 0.9f, 0f, 15f,
                        0.349f * 1.1f, 0.686f * 0.95f, 0.168f * 0.9f, 0f, 8f,
                        0.272f * 0.9f, 0.534f * 0.9f, 0.131f * 1.1f, 0f, -5f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                ColorFilter.colorMatrix(matrix)
            }
            "Black & White" -> {
                val matrix = ColorMatrix().apply { setToSaturation(0f) }
                ColorFilter.colorMatrix(matrix)
            }
            else -> null
        }
    }

    /**
     * Builds the complete post-framing FFmpeg filter chain (reframe zoom + crop offset, mirror, look filter).
     */
    fun buildPostProcessingFilterChain(
        selectedFilter: String,
        isMirrored: Boolean,
        manualReframeOffsetX: Float, // -1.0f to 1.0f
        manualReframeZoom: Float // 1.0f to 1.5f
    ): String {
        val filters = mutableListOf<String>()

        // 1. Manual Reframe Zoom & Offset (applied on 1080x1920 canvas)
        if (manualReframeZoom > 1.01f || kotlin.math.abs(manualReframeOffsetX) > 0.01f) {
            val z = manualReframeZoom.coerceIn(1.0f, 1.5f)
            val cropW = (1080 / z).toInt()
            val cropH = (1920 / z).toInt()
            val maxShiftX = (1080 - cropW) / 2
            val shiftX = (manualReframeOffsetX * maxShiftX).toInt()
            val cropX = ((1080 - cropW) / 2 + shiftX).coerceIn(0, 1080 - cropW)
            val cropY = ((1920 - cropH) / 2).coerceIn(0, 1920 - cropH)
            filters.add("crop=$cropW:$cropH:$cropX:$cropY,scale=1080:1920")
        }

        // 2. Horizontal Flip (Mirror)
        if (isMirrored) {
            filters.add("hflip")
        }

        // 3. Color Filter Look
        val lookFilter = getFfmpegFilterString(selectedFilter)
        if (lookFilter.isNotBlank()) {
            filters.add(lookFilter)
        }

        return filters.joinToString(",")
    }
}
