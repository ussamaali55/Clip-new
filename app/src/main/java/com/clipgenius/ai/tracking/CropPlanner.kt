package com.clipgenius.ai.tracking

import com.clipgenius.ai.state.ClipCropPlan
import com.clipgenius.ai.state.CropKeyframe
import com.clipgenius.ai.state.FaceSample
import com.clipgenius.ai.state.TrackedFace
import com.clipgenius.ai.state.TrackingMode
import com.clipgenius.ai.state.TranscriptSegment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Phase 9 Crop Planner:
 * Converts face detection samples into a smooth, dynamic 9:16 crop window path.
 * Features:
 * - Primary face selection (largest / most consistent, diarization aware)
 * - Headroom positioning (face sits comfortably in upper-middle third, never clipped at top)
 * - Eased moving-average smoothing with speed limiting to prevent motion sickness
 * - No-face fallback: movement-directed crop -> static center crop
 * - Interactive manual offsets: crop position (left<->right) & face margin
 */
object CropPlanner {

    private const val SMOOTHING_WINDOW_SAMPLES = 2 // ±1.0s window at 2 FPS (5-sample moving average)
    private const val MAX_PAN_SPEED_PX_PER_SEC = 450 // Max camera glide speed to avoid disorientation
    private const val TARGET_ASPECT = 9.0 / 16.0 // 0.5625

    fun planCrop(
        clipId: String,
        sourceWidth: Int,
        sourceHeight: Int,
        samples: List<FaceSample>,
        transcriptSegments: List<TranscriptSegment> = emptyList(),
        clipStartOffsetMs: Long = 0L,
        manualCropOffsetX: Float = 0f,
        manualFaceMargin: Float = 1.0f
    ): ClipCropPlan {
        if (sourceWidth <= 0 || sourceHeight <= 0 || samples.isEmpty()) {
            val defCropW = ((sourceHeight * TARGET_ASPECT).toInt().let { if (it % 2 != 0) it - 1 else it }).coerceAtMost(sourceWidth)
            val defCropH = sourceHeight
            val defX = max(0, (sourceWidth - defCropW) / 2)
            return ClipCropPlan(
                clipId = clipId,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                cropWidth = defCropW,
                cropHeight = defCropH,
                keyframes = emptyList(),
                manualCropOffsetX = manualCropOffsetX,
                manualFaceMargin = manualFaceMargin,
                dominantMode = TrackingMode.CENTER
            )
        }

        // 1. Calculate base 9:16 crop dimensions inside source bounds
        val baseCropHeight = sourceHeight
        var calculatedCropWidth = (sourceHeight * TARGET_ASPECT).roundToInt()
        // libx264 requires even width and height
        if (calculatedCropWidth % 2 != 0) calculatedCropWidth -= 1

        val finalCropWidth = calculatedCropWidth.coerceAtMost(sourceWidth)
        val finalCropHeight = baseCropHeight

        val maxCropX = max(0, sourceWidth - finalCropWidth)
        val maxCropY = max(0, sourceHeight - finalCropHeight)

        // 2. Select primary face ID across all samples
        val primaryTrackingId = selectPrimaryFaceId(samples, transcriptSegments, clipStartOffsetMs)

        // 3. Generate raw target centers for each sample
        val rawTargets = samples.map { sample ->
            val primaryFace = sample.faces.find { it.trackingId == primaryTrackingId }
                ?: sample.faces.maxByOrNull { (it.right - it.left) * (it.bottom - it.top) }

            when {
                primaryFace != null -> {
                    // Face mode: center on face horizontal center
                    val faceCenterX = ((primaryFace.left + primaryFace.right) / 2.0f) * sourceWidth
                    val faceCenterY = ((primaryFace.top + primaryFace.bottom) / 2.0f) * sourceHeight

                    // Headroom calculation: position face ~38% from top of crop
                    val desiredTop = faceCenterY - (finalCropHeight * 0.38f)
                    val clampedY = desiredTop.toInt().coerceIn(0, maxCropY)

                    RawTarget(
                        timestampMs = sample.timestampMs,
                        centerX = faceCenterX,
                        topY = clampedY.toFloat(),
                        mode = TrackingMode.FACE,
                        face = primaryFace
                    )
                }
                sample.movementScore > 0.08f -> {
                    // Movement mode fallback: center on movement direction
                    val movX = sample.movementCenterX * sourceWidth
                    RawTarget(
                        timestampMs = sample.timestampMs,
                        centerX = movX,
                        topY = 0f,
                        mode = TrackingMode.MOVEMENT,
                        face = null
                    )
                }
                else -> {
                    // Center mode fallback
                    RawTarget(
                        timestampMs = sample.timestampMs,
                        centerX = sourceWidth / 2.0f,
                        topY = 0f,
                        mode = TrackingMode.CENTER,
                        face = null
                    )
                }
            }
        }

        // 4. Smooth targets using moving average and speed limiting
        val smoothedKeyframes = mutableListOf<CropKeyframe>()
        var previousX = (sourceWidth - finalCropWidth) / 2.0f

        val manualPixelOffset = manualCropOffsetX * (maxCropX / 2.0f)

        for (i in rawTargets.indices) {
            val current = rawTargets[i]

            // Moving average over surrounding window (easing)
            val windowStart = max(0, i - SMOOTHING_WINDOW_SAMPLES)
            val windowEnd = min(rawTargets.size - 1, i + SMOOTHING_WINDOW_SAMPLES)
            val window = rawTargets.subList(windowStart, windowEnd + 1)

            val avgCenterX = window.map { it.centerX.toDouble() }.average().toFloat()

            // Desired top-left X position from center
            var targetX = avgCenterX - (finalCropWidth / 2.0f) + manualPixelOffset

            // Apply camera pan speed limit relative to previous keyframe
            if (i > 0) {
                val dtSec = max(0.1f, (current.timestampMs - rawTargets[i - 1].timestampMs) / 1000.0f)
                val maxDelta = MAX_PAN_SPEED_PX_PER_SEC * dtSec
                val delta = targetX - previousX
                if (abs(delta) > maxDelta) {
                    targetX = previousX + (if (delta > 0) maxDelta else -maxDelta)
                }
            }

            val clampedX = targetX.roundToInt().coerceIn(0, maxCropX)
            val clampedY = current.topY.roundToInt().coerceIn(0, maxCropY)

            // Ensure even values for encoder compatibility
            val evenX = if (clampedX % 2 != 0) (clampedX - 1).coerceAtLeast(0) else clampedX
            val evenY = if (clampedY % 2 != 0) (clampedY - 1).coerceAtLeast(0) else clampedY

            previousX = evenX.toFloat()

            smoothedKeyframes.add(
                CropKeyframe(
                    timestampMs = current.timestampMs,
                    cropX = evenX,
                    cropY = evenY,
                    cropWidth = finalCropWidth,
                    cropHeight = finalCropHeight,
                    mode = current.mode,
                    faceBox = current.face
                )
            )
        }

        // Determine dominant mode for UI indicators
        val faceCount = smoothedKeyframes.count { it.mode == TrackingMode.FACE }
        val movCount = smoothedKeyframes.count { it.mode == TrackingMode.MOVEMENT }
        val dominantMode = when {
            faceCount >= smoothedKeyframes.size / 3 -> TrackingMode.FACE
            movCount >= smoothedKeyframes.size / 3 -> TrackingMode.MOVEMENT
            else -> TrackingMode.CENTER
        }

        return ClipCropPlan(
            clipId = clipId,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            cropWidth = finalCropWidth,
            cropHeight = finalCropHeight,
            keyframes = smoothedKeyframes,
            manualCropOffsetX = manualCropOffsetX,
            manualFaceMargin = manualFaceMargin,
            dominantMode = dominantMode
        )
    }

    /**
     * Finds the primary face tracking ID that dominates the clip or speaks during active segments.
     */
    private fun selectPrimaryFaceId(
        samples: List<FaceSample>,
        transcriptSegments: List<TranscriptSegment>,
        clipStartOffsetMs: Long
    ): Int? {
        val faceCounts = mutableMapOf<Int, Int>()
        val faceSizes = mutableMapOf<Int, Float>()
        val speakingFaceScores = mutableMapOf<Int, Float>()

        for (sample in samples) {
            val absTimeMs = clipStartOffsetMs + sample.timestampMs
            val isSpeakingNow = transcriptSegments.any { seg ->
                absTimeMs >= seg.startMs && absTimeMs <= seg.endMs && seg.text.isNotBlank()
            }

            for (face in sample.faces) {
                val id = face.trackingId ?: continue
                val area = (face.right - face.left) * (face.bottom - face.top)
                faceCounts[id] = (faceCounts[id] ?: 0) + 1
                faceSizes[id] = (faceSizes[id] ?: 0f) + area
                if (isSpeakingNow) {
                    speakingFaceScores[id] = (speakingFaceScores[id] ?: 0f) + (1.5f + area * 2.0f)
                }
            }
        }

        if (faceCounts.isEmpty()) return null

        // Select the tracking ID with the highest presence, face area, and speaking segment correlation
        return faceCounts.maxByOrNull { (id, count) ->
            val totalArea = faceSizes[id] ?: 0f
            val speakScore = speakingFaceScores[id] ?: 0f
            count * (1.0f + totalArea) + speakScore * 2.0f
        }?.key
    }

    /**
     * Builds the reliable FFmpeg crop filter string.
     * Strategy: Compresses the smoothed keyframe path into anchor pan segments to evaluate
     * frame-accurate x/y transitions without overflowing command line length limits.
     */
    fun buildFfmpegCropFilter(plan: ClipCropPlan): String {
        val w = plan.cropWidth
        val h = plan.cropHeight

        if (plan.keyframes.isEmpty()) {
            val cx = max(0, (plan.sourceWidth - w) / 2)
            return "crop=$w:$h:$cx:0"
        }

        // Downsample keyframes to significant anchor points (every ~2 seconds or on significant pan)
        val anchors = mutableListOf<CropKeyframe>()
        anchors.add(plan.keyframes.first())

        for (i in 1 until plan.keyframes.size) {
            val curr = plan.keyframes[i]
            val last = anchors.last()
            val timeDiffMs = curr.timestampMs - last.timestampMs
            val posDiff = abs(curr.cropX - last.cropX)

            if (timeDiffMs >= 2000L || posDiff >= 40) {
                anchors.add(curr)
            }
        }
        if (anchors.last().timestampMs != plan.keyframes.last().timestampMs) {
            anchors.add(plan.keyframes.last())
        }

        if (anchors.size <= 1) {
            return "crop=$w:$h:${anchors.first().cropX}:${anchors.first().cropY}"
        }

        // Build piecewise linear interpolation expression for X:
        // x='if(lte(t, t1), x0 + (x1-x0)*(t-t0)/(t1-t0), if(lte(t, t2), ...))'
        val exprBuilder = StringBuilder()
        var openParens = 0

        for (i in 0 until anchors.size - 1) {
            val a0 = anchors[i]
            val a1 = anchors[i + 1]
            val t0 = a0.timestampMs / 1000.0
            val t1 = a1.timestampMs / 1000.0
            val x0 = a0.cropX
            val x1 = a1.cropX
            val dt = max(0.1, t1 - t0)

            exprBuilder.append("if(lte(t,${String.format(java.util.Locale.US, "%.2f", t1)}),")
            exprBuilder.append("$x0+($x1-$x0)*(t-$t0)/$dt,")
            openParens++
        }

        // Terminal fallback value (final keyframe position)
        exprBuilder.append("${anchors.last().cropX}")
        for (p in 0 until openParens) {
            exprBuilder.append(")")
        }

        val maxAllowedX = max(0, plan.sourceWidth - w)
        val finalXExpr = "min(max(0,${exprBuilder}),$maxAllowedX)"
        val staticY = anchors.first().cropY

        return "crop=$w:$h:'$finalXExpr':$staticY"
    }

    private data class RawTarget(
        val timestampMs: Long,
        val centerX: Float,
        val topY: Float,
        val mode: TrackingMode,
        val face: TrackedFace?
    )
}
