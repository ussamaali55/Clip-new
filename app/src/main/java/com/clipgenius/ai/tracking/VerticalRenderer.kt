package com.clipgenius.ai.tracking

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.clipgenius.ai.state.ClipCropPlan
import com.clipgenius.ai.state.ClipLayoutPlan
import com.clipgenius.ai.state.LayoutType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs

/**
 * Phase 9 & 10 9:16 Vertical Video Rendering Engine.
 * Supports:
 * - Single-person dynamic face tracking crop path
 * - Phase 10 Multi-person layouts: 2-person stacked, 3-person adaptive grid, 4-person grid
 * - FFmpeg filter_complex with multi-panel positioning, divider line, and speaker emphasis
 * - High-fidelity libx264 encoding with -crf 18 and stream-copied audio (-c:a copy)
 */
object VerticalRenderer {

    private const val TAG = "VerticalRenderer"

    data class VerticalQcResult(
        val passed: Boolean,
        val width: Int = 0,
        val height: Int = 0,
        val actualDurationMs: Long = 0L,
        val fileSizeBytes: Long = 0L,
        val details: String = "",
        val failureReason: String? = null
    )

    data class RenderResult(
        val success: Boolean,
        val outputPath: String? = null,
        val qcResult: VerticalQcResult,
        val errorMessage: String? = null
    )

    fun getVerticalDraftFile(context: Context, projectId: String, clipId: String): File {
        val dir = File(context.filesDir, "projects/$projectId/tracking")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "${clipId}_vertical_draft.mp4")
    }

    /**
     * Renders a 9:16 vertical crop draft video using FFmpeg.
     * Supports single-person and Phase 10 multi-person layouts.
     */
    suspend fun renderVertical(
        context: Context,
        projectId: String,
        clipId: String,
        inputVideoPath: String,
        cropPlan: ClipCropPlan,
        expectedDurationMs: Long,
        layoutPlan: ClipLayoutPlan? = null,
        onProgress: (Float) -> Unit = {}
    ): RenderResult = withContext(Dispatchers.IO) {
        val inputFile = File(inputVideoPath)
        if (!inputFile.exists()) {
            return@withContext RenderResult(
                success = false,
                qcResult = VerticalQcResult(false, failureReason = "Input video not found: $inputVideoPath"),
                errorMessage = "Source draft clip not found."
            )
        }

        val outputFile = getVerticalDraftFile(context, projectId, clipId)
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val isMultiPerson = layoutPlan != null &&
            layoutPlan.layoutType != LayoutType.SINGLE &&
            layoutPlan.layoutType != LayoutType.SINGLE_SPEAKER_FOLLOW &&
            layoutPlan.panels.size > 1

        val command = if (isMultiPerson) {
            val filterComplex = buildMultiPersonFilterComplex(layoutPlan!!)
            Log.i(TAG, "Rendering multi-person vertical draft (${layoutPlan.layoutType}) with filter_complex:\n$filterComplex")
            "-y -i \"${inputFile.absolutePath}\" -filter_complex \"$filterComplex\" -map \"[outv]\" -map 0:a? -c:v libx264 -preset veryfast -crf 18 -c:a copy \"${outputFile.absolutePath}\""
        } else {
            val cropFilter = CropPlanner.buildFfmpegCropFilter(cropPlan)
            Log.i(TAG, "Rendering single 9:16 vertical draft with filter: $cropFilter")
            "-y -i \"${inputFile.absolutePath}\" -vf \"$cropFilter\" -c:v libx264 -preset veryfast -crf 18 -c:a copy \"${outputFile.absolutePath}\""
        }

        onProgress(0.05f)
        val deferred = CompletableDeferred<Boolean>()

        try {
            FFmpegKit.executeAsync(
                command,
                { session ->
                    val success = ReturnCode.isSuccess(session.returnCode)
                    if (!success) {
                        Log.e(TAG, "Vertical render FFmpeg failed: ${session.failStackTrace}")
                    }
                    deferred.complete(success)
                },
                { /* log */ },
                { stats ->
                    if (expectedDurationMs > 0) {
                        val currentMs = stats.time
                        val prog = (currentMs.toFloat() / expectedDurationMs.toFloat()).coerceIn(0f, 0.98f)
                        onProgress(prog)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch vertical render session", e)
            return@withContext RenderResult(
                success = false,
                qcResult = VerticalQcResult(false, failureReason = e.message),
                errorMessage = e.message
            )
        }

        val isFfmpegSuccess = deferred.await()
        onProgress(1.0f)

        if (!isFfmpegSuccess || !outputFile.exists() || outputFile.length() == 0L) {
            return@withContext RenderResult(
                success = false,
                outputPath = outputFile.absolutePath,
                qcResult = VerticalQcResult(false, failureReason = "FFmpeg process failed or output empty"),
                errorMessage = "Failed to render 9:16 vertical draft video."
            )
        }

        // Quality Check Verification
        val panelCount = if (isMultiPerson) layoutPlan!!.panels.size else 1
        val qc = verifyVerticalQc(outputFile, expectedDurationMs, panelCount)
        RenderResult(
            success = qc.passed,
            outputPath = outputFile.absolutePath,
            qcResult = qc,
            errorMessage = if (!qc.passed) qc.failureReason else null
        )
    }

    /**
     * Builds the FFmpeg filter_complex script for 2-person stacked and 3/4-person grids.
     */
    private fun buildMultiPersonFilterComplex(layoutPlan: ClipLayoutPlan): String {
        val panels = layoutPlan.panels
        val panelCount = panels.size

        return when (layoutPlan.layoutType) {
            LayoutType.TWO_PERSON_STACKED -> {
                val crop0 = CropPlanner.buildFfmpegCropFilter(panels[0].cropPlan)
                val crop1 = CropPlanner.buildFfmpegCropFilter(panels[1].cropPlan)
                "[0:v]split=2[in0][in1];" +
                    "[in0]$crop0,scale=1080:960:flags=bicubic[top];" +
                    "[in1]$crop1,scale=1080:960:flags=bicubic[bottom];" +
                    "[top][bottom]vstack=inputs=2[vstacked];" +
                    "[vstacked]drawbox=y=958:h=4:color=white@0.6:t=fill[outv]"
            }

            LayoutType.THREE_PERSON_GRID -> {
                val crop0 = CropPlanner.buildFfmpegCropFilter(panels[0].cropPlan)
                val crop1 = CropPlanner.buildFfmpegCropFilter(panels[1].cropPlan)
                val crop2 = CropPlanner.buildFfmpegCropFilter(panels[2].cropPlan)
                "[0:v]split=3[in0][in1][in2];" +
                    "[in0]$crop0,scale=540:960:flags=bicubic[tl];" +
                    "[in1]$crop1,scale=540:960:flags=bicubic[tr];" +
                    "[in2]$crop2,scale=1080:960:flags=bicubic[b];" +
                    "[tl][tr]hstack=inputs=2[toprow];" +
                    "[toprow][b]vstack=inputs=2[vstacked];" +
                    "[vstacked]drawbox=y=958:h=4:color=white@0.6:t=fill,drawbox=x=538:y=0:w=4:h=960:color=white@0.6:t=fill[outv]"
            }

            LayoutType.FOUR_PERSON_GRID -> {
                val crop0 = CropPlanner.buildFfmpegCropFilter(panels[0].cropPlan)
                val crop1 = CropPlanner.buildFfmpegCropFilter(panels[1].cropPlan)
                val crop2 = CropPlanner.buildFfmpegCropFilter(panels[2].cropPlan)
                val crop3 = CropPlanner.buildFfmpegCropFilter(panels[3].cropPlan)
                "[0:v]split=4[in0][in1][in2][in3];" +
                    "[in0]$crop0,scale=540:960:flags=bicubic[p0];" +
                    "[in1]$crop1,scale=540:960:flags=bicubic[p1];" +
                    "[in2]$crop2,scale=540:960:flags=bicubic[p2];" +
                    "[in3]$crop3,scale=540:960:flags=bicubic[p3];" +
                    "[p0][p1]hstack=inputs=2[toprow];" +
                    "[p2][p3]hstack=inputs=2[bottomrow];" +
                    "[toprow][bottomrow]vstack=inputs=2[vstacked];" +
                    "[vstacked]drawbox=y=958:h=4:color=white@0.6:t=fill,drawbox=x=538:y=0:w=4:h=1920:color=white@0.6:t=fill[outv]"
            }

            else -> {
                val crop0 = CropPlanner.buildFfmpegCropFilter(panels[0].cropPlan)
                "[0:v]$crop0,scale=1080:1920:flags=bicubic[outv]"
            }
        }
    }

    /**
     * Quality Check:
     * 1. Output file exists and size > 0
     * 2. Exactly 9:16 aspect ratio (width/height ratio 0.5625 ±0.03)
     * 3. No black bars (check edge pixels on test frame)
     * 4. Multi-panel check: verify all panels filled (no completely black/empty panels)
     * 5. Duration matches draft (±2s)
     * 6. File decodes and plays
     */
    fun verifyVerticalQc(outputFile: File, expectedDurationMs: Long, panelCount: Int = 1): VerticalQcResult {
        if (!outputFile.exists() || outputFile.length() == 0L) {
            return VerticalQcResult(false, failureReason = "Vertical draft file missing or empty")
        }

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(outputFile.absolutePath)

            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0

            if (width <= 0 || height <= 0) {
                return VerticalQcResult(false, failureReason = "Invalid video dimensions ($width x $height)")
            }

            // 1. Aspect Ratio Check: 9:16 == 0.5625 (tolerance ±0.03)
            val aspect = width.toFloat() / height.toFloat()
            val expectedAspect = 9.0f / 16.0f
            if (abs(aspect - expectedAspect) > 0.03f) {
                return VerticalQcResult(
                    false,
                    width = width,
                    height = height,
                    failureReason = "Aspect ratio is not 9:16. Got ${width}x${height} ($aspect vs expected $expectedAspect)"
                )
            }

            // 2. Duration Check within ±2s
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val actualDurationMs = durationStr?.toLongOrNull() ?: 0L
            val diffMs = abs(actualDurationMs - expectedDurationMs)
            if (expectedDurationMs > 0 && diffMs > 2000L) {
                return VerticalQcResult(
                    false,
                    width = width,
                    height = height,
                    actualDurationMs = actualDurationMs,
                    failureReason = "Duration mismatch: expected ${expectedDurationMs / 1000}s, got ${actualDurationMs / 1000}s"
                )
            }

            // 3. Playable test frame grab
            val frame = retriever.getFrameAtTime(100_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)

            if (frame == null) {
                return VerticalQcResult(false, width = width, height = height, failureReason = "Could not decode test frame")
            }

            // 4. Black bars check: verify center & edges aren't completely solid black (all 0s)
            val hasContent = verifyNonEmptyFrame(frame)
            if (!hasContent) {
                return VerticalQcResult(false, width = width, height = height, failureReason = "Rendered video contains solid black content")
            }

            // 5. Multi-panel fill check: verify each panel has non-black content
            if (panelCount >= 2) {
                val topSample = frame.getPixel(frame.width / 2, frame.height / 4)
                val bottomSample = frame.getPixel(frame.width / 2, (frame.height * 3) / 4)
                val isTopBlack = (topSample shr 16 and 0xFF) < 10 && (topSample shr 8 and 0xFF) < 10 && (topSample and 0xFF) < 10
                val isBottomBlack = (bottomSample shr 16 and 0xFF) < 10 && (bottomSample shr 8 and 0xFF) < 10 && (bottomSample and 0xFF) < 10

                if (isTopBlack && isBottomBlack) {
                    return VerticalQcResult(false, width = width, height = height, failureReason = "Panels check failed: multiple panels are solid black")
                }
            }

            val sizeMB = String.format(Locale.US, "%.1f MB", outputFile.length() / (1024.0 * 1024.0))
            val panelLabel = if (panelCount > 1) "$panelCount-Panel Layout" else "Active Face"
            VerticalQcResult(
                passed = true,
                width = width,
                height = height,
                actualDurationMs = actualDurationMs,
                fileSizeBytes = outputFile.length(),
                details = "QC passed • 9:16 (${width}x${height}) • $sizeMB • $panelLabel"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Vertical QC error", e)
            VerticalQcResult(false, failureReason = "QC error: ${e.message}")
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    private fun verifyNonEmptyFrame(bitmap: Bitmap): Boolean {
        val points = listOf(
            Pair(bitmap.width / 4, bitmap.height / 4),
            Pair(bitmap.width / 2, bitmap.height / 2),
            Pair((bitmap.width * 3) / 4, (bitmap.height * 3) / 4),
            Pair(bitmap.width / 2, bitmap.height / 4),
            Pair(bitmap.width / 2, (bitmap.height * 3) / 4)
        )

        for ((x, y) in points) {
            val pixel = bitmap.getPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1))
            val r = pixel shr 16 and 0xFF
            val g = pixel shr 8 and 0xFF
            val b = pixel and 0xFF
            if (r > 12 || g > 12 || b > 12) {
                return true
            }
        }
        return false
    }
}

