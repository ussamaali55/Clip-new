package com.clipgenius.ai.tracking

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import com.clipgenius.ai.state.FaceSample
import com.clipgenius.ai.state.TrackedFace
import com.clipgenius.ai.state.TrackingMode
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Phase 9 Face Track Analyzer:
 * Samples cut clip frames at 2 frames per second (every 500ms).
 * Runs Google ML Kit on-device face detection on each sampled frame.
 * Detects movement differences as fallback when no face is found.
 * Caches track to filesDir/projects/{projectId}/tracking/{clipId}_facetrack.json.
 */
object FaceTrackAnalyzer {

    private const val TAG = "FaceTrackAnalyzer"
    private const val SAMPLE_INTERVAL_MS = 500L // 2 FPS

    data class FaceTrackResult(
        val clipId: String,
        val videoWidth: Int,
        val videoHeight: Int,
        val durationMs: Long,
        val samples: List<FaceSample>,
        val jsonPath: String
    )

    fun getTrackFile(context: Context, projectId: String, clipId: String): File {
        val dir = File(context.filesDir, "projects/$projectId/tracking")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "${clipId}_facetrack.json")
    }

    /**
     * Analyzes video frames with ML Kit Face Detection.
     * Caches track to disk — never re-analyzes unless forceReanalyze is true.
     */
    suspend fun analyzeClip(
        context: Context,
        projectId: String,
        clipId: String,
        videoPath: String,
        forceReanalyze: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): FaceTrackResult = withContext(Dispatchers.IO) {
        val trackFile = getTrackFile(context, projectId, clipId)

        // 1. Check disk cache
        if (!forceReanalyze && trackFile.exists() && trackFile.length() > 0) {
            val cached = loadCachedTrack(trackFile, clipId)
            if (cached != null) {
                Log.i(TAG, "Loaded cached face track for clip $clipId (${cached.samples.size} samples)")
                onProgress(1f)
                return@withContext cached
            }
        }

        val videoFile = File(videoPath)
        if (!videoFile.exists()) {
            throw IllegalArgumentException("Draft video file not found at: $videoPath")
        }

        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(videoFile.absolutePath)

        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 1000L
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080

        val detectorOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .enableTracking()
            .build()
        val detector = FaceDetection.getClient(detectorOptions)

        val samples = mutableListOf<FaceSample>()
        var previousFrameThumb: Bitmap? = null

        val totalSamples = max(1L, durationMs / SAMPLE_INTERVAL_MS)
        var sampleIndex = 0

        try {
            var currentMs = 0L
            while (currentMs < durationMs) {
                val progress = (sampleIndex.toFloat() / totalSamples.toFloat()).coerceIn(0f, 0.95f)
                onProgress(progress)

                val frameBitmap = runCatching {
                    retriever.getFrameAtTime(currentMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.getFrameAtTime(currentMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)
                }.getOrNull()

                if (frameBitmap != null) {
                    val inputImage = InputImage.fromBitmap(frameBitmap, 0)
                    val mlKitFaces = runCatching {
                        Tasks.await(detector.process(inputImage))
                    }.getOrDefault(emptyList())

                    val trackedFaces = mlKitFaces.map { face ->
                        val box = face.boundingBox
                        TrackedFace(
                            left = (box.left.toFloat() / frameBitmap.width.toFloat()).coerceIn(0f, 1f),
                            top = (box.top.toFloat() / frameBitmap.height.toFloat()).coerceIn(0f, 1f),
                            right = (box.right.toFloat() / frameBitmap.width.toFloat()).coerceIn(0f, 1f),
                            bottom = (box.bottom.toFloat() / frameBitmap.height.toFloat()).coerceIn(0f, 1f),
                            trackingId = face.trackingId,
                            confidence = 1.0f
                        )
                    }

                    // Compute movement difference if no faces detected
                    val currentThumb = Bitmap.createScaledBitmap(frameBitmap, 32, 18, true)
                    val (movScore, movCenter) = if (previousFrameThumb != null) {
                        computeMovementDifference(previousFrameThumb, currentThumb)
                    } else {
                        Pair(0f, 0.5f)
                    }
                    previousFrameThumb?.recycle()
                    previousFrameThumb = currentThumb

                    val mode = when {
                        trackedFaces.isNotEmpty() -> TrackingMode.FACE
                        movScore > 0.08f -> TrackingMode.MOVEMENT
                        else -> TrackingMode.CENTER
                    }

                    samples.add(
                        FaceSample(
                            timestampMs = currentMs,
                            faces = trackedFaces,
                            movementScore = movScore,
                            movementCenterX = movCenter,
                            mode = mode
                        )
                    )
                } else {
                    // Frame grab fallback
                    samples.add(
                        FaceSample(
                            timestampMs = currentMs,
                            faces = emptyList(),
                            movementScore = 0f,
                            movementCenterX = 0.5f,
                            mode = TrackingMode.CENTER
                        )
                    )
                }

                currentMs += SAMPLE_INTERVAL_MS
                sampleIndex++
            }
        } finally {
            try {
                retriever.release()
                detector.close()
                previousFrameThumb?.recycle()
            } catch (_: Exception) {}
        }

        // 2. Save result to disk cache
        saveTrackToDisk(trackFile, clipId, width, height, durationMs, samples)
        onProgress(1f)

        FaceTrackResult(
            clipId = clipId,
            videoWidth = width,
            videoHeight = height,
            durationMs = durationMs,
            samples = samples,
            jsonPath = trackFile.absolutePath
        )
    }

    /**
     * Compares two downsampled frames to estimate motion intensity and horizontal center of motion.
     */
    private fun computeMovementDifference(prev: Bitmap, curr: Bitmap): Pair<Float, Float> {
        val w = prev.width
        val h = prev.height
        var totalDiff = 0.0
        val columnDiffs = DoubleArray(w)

        for (x in 0 until w) {
            var colDiff = 0.0
            for (y in 0 until h) {
                val p1 = prev.getPixel(x, y)
                val p2 = curr.getPixel(x, y)

                val rDiff = abs((p1 shr 16 and 0xFF) - (p2 shr 16 and 0xFF))
                val gDiff = abs((p1 shr 8 and 0xFF) - (p2 shr 8 and 0xFF))
                val bDiff = abs((p1 and 0xFF) - (p2 and 0xFF))
                val diff = (rDiff + gDiff + bDiff) / 3.0
                colDiff += diff
            }
            columnDiffs[x] = colDiff
            totalDiff += colDiff
        }

        val maxPossibleDiff = (w * h * 255.0)
        val normalizedDiffScore = (totalDiff / maxPossibleDiff).toFloat().coerceIn(0f, 1f)

        // Find weighted horizontal center of motion
        var weightedSum = 0.0
        var weightTotal = 0.0
        for (x in 0 until w) {
            val weight = columnDiffs[x]
            weightedSum += x * weight
            weightTotal += weight
        }

        val normalizedCenterX = if (weightTotal > 0) {
            (weightedSum / weightTotal / w.toDouble()).toFloat().coerceIn(0.1f, 0.9f)
        } else {
            0.5f
        }

        return Pair(normalizedDiffScore, normalizedCenterX)
    }

    private fun saveTrackToDisk(
        file: File,
        clipId: String,
        width: Int,
        height: Int,
        durationMs: Long,
        samples: List<FaceSample>
    ) {
        val root = JSONObject()
        root.put("clipId", clipId)
        root.put("videoWidth", width)
        root.put("videoHeight", height)
        root.put("durationMs", durationMs)

        val samplesArr = JSONArray()
        for (sample in samples) {
            val sObj = JSONObject()
            sObj.put("timestampMs", sample.timestampMs)
            sObj.put("mode", sample.mode.name.lowercase())
            sObj.put("movementScore", sample.movementScore.toDouble())
            sObj.put("movementCenterX", sample.movementCenterX.toDouble())

            val facesArr = JSONArray()
            for (face in sample.faces) {
                val fObj = JSONObject()
                fObj.put("left", face.left.toDouble())
                fObj.put("top", face.top.toDouble())
                fObj.put("right", face.right.toDouble())
                fObj.put("bottom", face.bottom.toDouble())
                if (face.trackingId != null) fObj.put("trackingId", face.trackingId)
                fObj.put("confidence", face.confidence.toDouble())
                facesArr.put(fObj)
            }
            sObj.put("faces", facesArr)
            samplesArr.put(sObj)
        }
        root.put("samples", samplesArr)
        file.writeText(root.toString(2))
    }

    private fun loadCachedTrack(file: File, clipId: String): FaceTrackResult? {
        return runCatching {
            val json = JSONObject(file.readText())
            val width = json.optInt("videoWidth", 1920)
            val height = json.optInt("videoHeight", 1080)
            val durationMs = json.optLong("durationMs", 0L)
            val samplesArr = json.optJSONArray("samples") ?: return null

            val samples = mutableListOf<FaceSample>()
            for (i in 0 until samplesArr.length()) {
                val sObj = samplesArr.getJSONObject(i)
                val faces = mutableListOf<TrackedFace>()
                val facesArr = sObj.optJSONArray("faces")
                if (facesArr != null) {
                    for (j in 0 until facesArr.length()) {
                        val fObj = facesArr.getJSONObject(j)
                        faces.add(
                            TrackedFace(
                                left = fObj.optDouble("left", 0.0).toFloat(),
                                top = fObj.optDouble("top", 0.0).toFloat(),
                                right = fObj.optDouble("right", 0.0).toFloat(),
                                bottom = fObj.optDouble("bottom", 0.0).toFloat(),
                                trackingId = if (fObj.has("trackingId")) fObj.optInt("trackingId") else null,
                                confidence = fObj.optDouble("confidence", 1.0).toFloat()
                            )
                        )
                    }
                }

                val modeStr = sObj.optString("mode", TrackingMode.FACE.name)
                val mode = runCatching { TrackingMode.valueOf(modeStr.uppercase()) }.getOrDefault(TrackingMode.FACE)

                samples.add(
                    FaceSample(
                        timestampMs = sObj.optLong("timestampMs", 0L),
                        faces = faces,
                        movementScore = sObj.optDouble("movementScore", 0.0).toFloat(),
                        movementCenterX = sObj.optDouble("movementCenterX", 0.5).toFloat(),
                        mode = mode
                    )
                )
            }

            FaceTrackResult(
                clipId = clipId,
                videoWidth = width,
                videoHeight = height,
                durationMs = durationMs,
                samples = samples,
                jsonPath = file.absolutePath
            )
        }.getOrNull()
    }
}
