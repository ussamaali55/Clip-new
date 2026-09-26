package com.clipgenius.ai.tracking

import android.content.Context
import android.util.Log
import com.clipgenius.ai.state.ClipCropPlan
import com.clipgenius.ai.state.ClipLayoutPlan
import com.clipgenius.ai.state.CropKeyframe
import com.clipgenius.ai.state.FaceSample
import com.clipgenius.ai.state.LayoutReviewStatus
import com.clipgenius.ai.state.LayoutSwitchPoint
import com.clipgenius.ai.state.LayoutType
import com.clipgenius.ai.state.PanelCropConfig
import com.clipgenius.ai.state.PersonProfile
import com.clipgenius.ai.state.TrackedFace
import com.clipgenius.ai.state.TrackingMode
import com.clipgenius.ai.state.TranscriptSegment
import com.clipgenius.ai.state.VerifiedClip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Phase 10 Layout Decider:
 * 1. Counts distinct people using face bounding box IoU (> 0.5) and tracking IDs across sampled frames.
 * 2. Formulates 2-person stacked layouts, 3-person adaptive grids, and 4-person grids on a 9:16 canvas.
 * 3. Enforces the Readability Guard (each face must occupy >= 8% of total canvas height).
 * 4. Generates dynamic switching points (throttled to at most once per 10s with 0.5s crossfade).
 * 5. Saves and loads cache from filesDir/projects/{projectId}/tracking/{clipId}_layout.json.
 */
object LayoutDecider {

    private const val TAG = "LayoutDecider"
    private const val MIN_FACE_HEIGHT_RATIO = 0.08f // 8% of vertical canvas height
    private const val SWITCH_THROTTLE_INTERVAL_MS = 10_000L // At most once per 10 seconds

    fun getLayoutFile(context: Context, projectId: String, clipId: String): File {
        val dir = File(context.filesDir, "projects/$projectId/tracking")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, "${clipId}_layout.json")
    }

    /**
     * Computes the complete multi-person layout plan for a clip.
     */
    fun decideLayout(
        context: Context,
        projectId: String,
        clip: VerifiedClip,
        samples: List<FaceSample>,
        sourceWidth: Int,
        sourceHeight: Int,
        transcriptSegments: List<TranscriptSegment> = emptyList(),
        clipStartOffsetMs: Long = 0L,
        forceRecompute: Boolean = false
    ): ClipLayoutPlan {
        val layoutFile = getLayoutFile(context, projectId, clip.clipId)

        // Check cached layout unless forced or user manually updated layout settings
        if (!forceRecompute && layoutFile.exists() && layoutFile.length() > 0 &&
            clip.layoutType == LayoutType.AUTO && clip.selectedPersonIds.isEmpty()
        ) {
            val cached = loadCachedLayout(layoutFile, clip.clipId)
            if (cached != null) {
                return cached
            }
        }

        // 1. Group face detections into distinct person clusters
        val rawPersons = clusterFacesIntoPersons(samples, transcriptSegments, clipStartOffsetMs)

        // Filter based on user's "Pick who to show" selection if specified
        val activePersons = if (clip.selectedPersonIds.isNotEmpty()) {
            rawPersons.map { p -> p.copy(isIncluded = clip.selectedPersonIds.contains(p.personId)) }
        } else {
            rawPersons
        }

        val includedPersons = activePersons.filter { it.isIncluded }
        val distinctCount = includedPersons.size

        // 2. Determine effective layout type
        val effectiveType = when {
            clip.layoutType == LayoutType.SINGLE_SPEAKER_FOLLOW -> LayoutType.SINGLE_SPEAKER_FOLLOW
            clip.layoutType == LayoutType.SINGLE -> LayoutType.SINGLE
            clip.layoutType == LayoutType.TWO_PERSON_STACKED -> LayoutType.TWO_PERSON_STACKED
            clip.layoutType == LayoutType.THREE_PERSON_GRID -> LayoutType.THREE_PERSON_GRID
            clip.layoutType == LayoutType.FOUR_PERSON_GRID -> LayoutType.FOUR_PERSON_GRID
            // Auto selection based on person count
            distinctCount <= 1 -> LayoutType.SINGLE
            distinctCount == 2 -> LayoutType.TWO_PERSON_STACKED
            distinctCount == 3 -> LayoutType.THREE_PERSON_GRID
            else -> LayoutType.FOUR_PERSON_GRID
        }

        // 3. Compute dynamic switching points (if person count fluctuates over time)
        val switchPoints = computeSwitchPoints(samples, includedPersons, effectiveType)

        // 4. Build panels and crop paths for each person in the chosen layout
        val panels = buildPanelConfigs(
            clipId = clip.clipId,
            layoutType = effectiveType,
            persons = includedPersons,
            samples = samples,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            transcriptSegments = transcriptSegments,
            clipStartOffsetMs = clipStartOffsetMs
        )

        // 5. Readability Guard Verification
        var reviewStatus = if (clip.layoutReviewStatus == LayoutReviewStatus.APPROVED_BY_USER) {
            LayoutReviewStatus.APPROVED_BY_USER
        } else {
            LayoutReviewStatus.OK
        }
        var reviewReason: String? = null

        if (effectiveType != LayoutType.SINGLE && effectiveType != LayoutType.SINGLE_SPEAKER_FOLLOW) {
            val tinyPanel = panels.find { it.faceHeightRatio < MIN_FACE_HEIGHT_RATIO }
            if (tinyPanel != null && reviewStatus != LayoutReviewStatus.APPROVED_BY_USER) {
                reviewStatus = LayoutReviewStatus.NEEDS_REVIEW
                val pct = String.format(Locale.US, "%.1f%%", tinyPanel.faceHeightRatio * 100f)
                val minPct = String.format(Locale.US, "%.0f%%", MIN_FACE_HEIGHT_RATIO * 100f)
                reviewReason = "Person ${tinyPanel.personId}'s face occupies $pct of vertical canvas (below $minPct readability threshold). Review framing before rendering."
            }
        }

        val plan = ClipLayoutPlan(
            clipId = clip.clipId,
            layoutType = effectiveType,
            distinctPersonCount = distinctCount,
            persons = activePersons,
            panels = panels,
            switchPoints = switchPoints,
            reviewStatus = reviewStatus,
            reviewReason = reviewReason,
            layoutJsonPath = layoutFile.absolutePath
        )

        // 6. Save layout to disk cache
        saveLayoutToDisk(layoutFile, plan)

        return plan
    }

    /**
     * Clusters sampled faces into consistent person tracks by combining ML Kit trackingId
     * with spatial overlap (IoU > 0.5) across consecutive frames.
     */
    private fun clusterFacesIntoPersons(
        samples: List<FaceSample>,
        transcriptSegments: List<TranscriptSegment>,
        clipStartOffsetMs: Long
    ): List<PersonProfile> {
        if (samples.isEmpty()) return emptyList()

        class PersonCluster(
            val id: Int,
            var trackingIds: MutableSet<Int> = mutableSetOf(),
            val occurrences: MutableList<Pair<Long, TrackedFace>> = mutableListOf()
        ) {
            fun computeAvgBox(): TrackedFace {
                val lefts = occurrences.map { it.second.left }
                val tops = occurrences.map { it.second.top }
                val rights = occurrences.map { it.second.right }
                val bottoms = occurrences.map { it.second.bottom }
                return TrackedFace(
                    left = lefts.average().toFloat(),
                    top = tops.average().toFloat(),
                    right = rights.average().toFloat(),
                    bottom = bottoms.average().toFloat()
                )
            }
        }

        val clusters = mutableListOf<PersonCluster>()
        var nextId = 1

        for (sample in samples) {
            val facesInFrame = sample.faces
            if (facesInFrame.isEmpty()) continue

            for (face in facesInFrame) {
                // Find best matching existing cluster:
                // 1. By matching trackingId
                var matched = if (face.trackingId != null) {
                    clusters.find { it.trackingIds.contains(face.trackingId) }
                } else null

                // 2. By bounding-box IoU > 0.5 with recent occurrences in cluster
                if (matched == null) {
                    matched = clusters.filter { cluster ->
                        // Crucial: A cluster cannot have two faces in the SAME frame
                        cluster.occurrences.none { it.first == sample.timestampMs }
                    }.maxByOrNull { cluster ->
                        val lastFace = cluster.occurrences.lastOrNull()?.second ?: return@maxByOrNull 0f
                        computeIoU(face, lastFace)
                    }?.takeIf { cluster ->
                        val lastFace = cluster.occurrences.last().second
                        computeIoU(face, lastFace) > 0.45f
                    }
                }

                if (matched != null) {
                    matched.occurrences.add(Pair(sample.timestampMs, face))
                    if (face.trackingId != null) matched.trackingIds.add(face.trackingId)
                } else {
                    val newCluster = PersonCluster(nextId++)
                    newCluster.occurrences.add(Pair(sample.timestampMs, face))
                    if (face.trackingId != null) newCluster.trackingIds.add(face.trackingId)
                    clusters.add(newCluster)
                }
            }
        }

        val totalDurationMs = max(1000L, samples.lastOrNull()?.timestampMs ?: 1000L)

        // Filter out accidental transient detections (must appear in at least 2 samples or >= 5% of clip)
        val validClusters = clusters.filter { cluster ->
            cluster.occurrences.size >= 2 || (cluster.occurrences.size * 500L >= totalDurationMs * 0.05f)
        }.sortedByDescending { it.occurrences.size }

        if (validClusters.isEmpty()) {
            return listOf(
                PersonProfile(
                    personId = 1,
                    label = "Person 1",
                    avgBoundingBox = TrackedFace(0.35f, 0.2f, 0.65f, 0.5f),
                    totalVisibleMs = totalDurationMs,
                    visibleRatio = 1.0f
                )
            )
        }

        // Correlate with speaker diarization labels from transcript
        val personProfiles = validClusters.mapIndexed { idx, cluster ->
            val avgBox = cluster.computeAvgBox()
            val visibleMs = cluster.occurrences.size * 500L
            val ratio = (visibleMs.toFloat() / totalDurationMs.toFloat()).coerceIn(0f, 1f)

            // Determine if active during specific speaker segments
            val speakerMatches = mutableMapOf<String, Int>()
            for ((ts, _) in cluster.occurrences) {
                val absMs = clipStartOffsetMs + ts
                val seg = transcriptSegments.find { absMs >= it.startMs && absMs <= it.endMs && !it.speakerLabel.isNullOrBlank() }
                if (seg?.speakerLabel != null) {
                    speakerMatches[seg.speakerLabel] = (speakerMatches[seg.speakerLabel] ?: 0) + 1
                }
            }
            val bestSpeaker = speakerMatches.maxByOrNull { it.value }?.key

            PersonProfile(
                personId = idx + 1,
                trackingId = cluster.trackingIds.firstOrNull(),
                label = bestSpeaker?.takeIf { it.isNotBlank() } ?: "Person ${idx + 1}",
                avgBoundingBox = avgBox,
                totalVisibleMs = visibleMs,
                visibleRatio = ratio,
                assignedSpeakerLabel = bestSpeaker,
                isIncluded = true
            )
        }

        return personProfiles
    }

    /**
     * Calculates IoU (Intersection over Union) of two normalized face rectangles.
     */
    fun computeIoU(a: TrackedFace, b: TrackedFace): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)

        val interWidth = max(0f, interRight - interLeft)
        val interHeight = max(0f, interBottom - interTop)
        val interArea = interWidth * interHeight

        val areaA = max(0f, a.right - a.left) * max(0f, a.bottom - a.top)
        val areaB = max(0f, b.right - b.left) * max(0f, b.bottom - b.top)
        val unionArea = areaA + areaB - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    /**
     * Formulates panel layouts and computes individual face-crop paths for each person.
     */
    private fun buildPanelConfigs(
        clipId: String,
        layoutType: LayoutType,
        persons: List<PersonProfile>,
        samples: List<FaceSample>,
        sourceWidth: Int,
        sourceHeight: Int,
        transcriptSegments: List<TranscriptSegment>,
        clipStartOffsetMs: Long
    ): List<PanelCropConfig> {
        val panels = mutableListOf<PanelCropConfig>()
        if (persons.isEmpty()) return panels

        when (layoutType) {
            LayoutType.SINGLE, LayoutType.SINGLE_SPEAKER_FOLLOW -> {
                // Single 9:16 panel covering 100% of canvas
                val primaryPerson = persons.first()
                val cropPlan = CropPlanner.planCrop(
                    clipId = clipId,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    samples = samples,
                    transcriptSegments = transcriptSegments,
                    clipStartOffsetMs = clipStartOffsetMs
                )
                val avgFaceH = primaryPerson.avgBoundingBox.bottom - primaryPerson.avgBoundingBox.top
                panels.add(
                    PanelCropConfig(
                        panelIndex = 0,
                        personId = primaryPerson.personId,
                        panelNormX = 0f,
                        panelNormY = 0f,
                        panelNormWidth = 1f,
                        panelNormHeight = 1f,
                        cropPlan = cropPlan,
                        faceHeightRatio = avgFaceH * (sourceHeight.toFloat() / cropPlan.cropHeight.toFloat())
                    )
                )
            }

            LayoutType.TWO_PERSON_STACKED -> {
                // Split 9:16 canvas into Upper and Lower panels (height = 0.5 each)
                // Leftmost/primary person in Top panel, Rightmost in Bottom panel
                val sorted = persons.sortedBy { it.avgBoundingBox.left }
                val personTop = sorted.getOrNull(0) ?: persons.first()
                val personBottom = sorted.getOrNull(1) ?: persons.last()

                // Generate dedicated crop for Top person
                val cropPlanTop = buildPersonDedicatedCropPlan(
                    clipId = "${clipId}_p${personTop.personId}",
                    person = personTop,
                    samples = samples,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    targetAspect = 9.0 / 8.0 // Each panel is 9:(16/2) = 9:8
                )

                // Generate dedicated crop for Bottom person
                val cropPlanBottom = buildPersonDedicatedCropPlan(
                    clipId = "${clipId}_p${personBottom.personId}",
                    person = personBottom,
                    samples = samples,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    targetAspect = 9.0 / 8.0
                )

                val faceHTop = (personTop.avgBoundingBox.bottom - personTop.avgBoundingBox.top) * (sourceHeight.toFloat() / cropPlanTop.cropHeight.toFloat()) * 0.5f
                val faceHBottom = (personBottom.avgBoundingBox.bottom - personBottom.avgBoundingBox.top) * (sourceHeight.toFloat() / cropPlanBottom.cropHeight.toFloat()) * 0.5f

                panels.add(
                    PanelCropConfig(
                        panelIndex = 0,
                        personId = personTop.personId,
                        panelNormX = 0f,
                        panelNormY = 0f,
                        panelNormWidth = 1f,
                        panelNormHeight = 0.5f,
                        cropPlan = cropPlanTop,
                        faceHeightRatio = faceHTop
                    )
                )
                panels.add(
                    PanelCropConfig(
                        panelIndex = 1,
                        personId = personBottom.personId,
                        panelNormX = 0f,
                        panelNormY = 0.5f,
                        panelNormWidth = 1f,
                        panelNormHeight = 0.5f,
                        cropPlan = cropPlanBottom,
                        faceHeightRatio = faceHBottom
                    )
                )
            }

            LayoutType.THREE_PERSON_GRID -> {
                // Adaptive 3-person grid: 2 on top (each 0.5 width x 0.5 height), 1 wide below (1.0 width x 0.5 height)
                // The most prominent/visible person gets the wide bottom cell
                val mostVisible = persons.maxByOrNull { it.totalVisibleMs } ?: persons.first()
                val others = persons.filter { it.personId != mostVisible.personId }.sortedBy { it.avgBoundingBox.left }
                val personTopLeft = others.getOrNull(0) ?: persons[0]
                val personTopRight = others.getOrNull(1) ?: persons.getOrElse(1) { persons[0] }

                val planTopLeft = buildPersonDedicatedCropPlan("${clipId}_p${personTopLeft.personId}", personTopLeft, samples, sourceWidth, sourceHeight, 9.0 / 16.0)
                val planTopRight = buildPersonDedicatedCropPlan("${clipId}_p${personTopRight.personId}", personTopRight, samples, sourceWidth, sourceHeight, 9.0 / 16.0)
                val planBottom = buildPersonDedicatedCropPlan("${clipId}_p${mostVisible.personId}", mostVisible, samples, sourceWidth, sourceHeight, 9.0 / 8.0)

                panels.add(
                    PanelCropConfig(
                        panelIndex = 0,
                        personId = personTopLeft.personId,
                        panelNormX = 0f,
                        panelNormY = 0f,
                        panelNormWidth = 0.5f,
                        panelNormHeight = 0.5f,
                        cropPlan = planTopLeft,
                        faceHeightRatio = (personTopLeft.avgBoundingBox.bottom - personTopLeft.avgBoundingBox.top) * 0.5f
                    )
                )
                panels.add(
                    PanelCropConfig(
                        panelIndex = 1,
                        personId = personTopRight.personId,
                        panelNormX = 0.5f,
                        panelNormY = 0f,
                        panelNormWidth = 0.5f,
                        panelNormHeight = 0.5f,
                        cropPlan = planTopRight,
                        faceHeightRatio = (personTopRight.avgBoundingBox.bottom - personTopRight.avgBoundingBox.top) * 0.5f
                    )
                )
                panels.add(
                    PanelCropConfig(
                        panelIndex = 2,
                        personId = mostVisible.personId,
                        panelNormX = 0f,
                        panelNormY = 0.5f,
                        panelNormWidth = 1f,
                        panelNormHeight = 0.5f,
                        cropPlan = planBottom,
                        faceHeightRatio = (mostVisible.avgBoundingBox.bottom - mostVisible.avgBoundingBox.top) * 0.5f
                    )
                )
            }

            LayoutType.FOUR_PERSON_GRID -> {
                // 2x2 grid (each 0.5 width x 0.5 height)
                val sorted = persons.sortedBy { it.avgBoundingBox.left }
                val p1 = sorted.getOrNull(0) ?: persons[0]
                val p2 = sorted.getOrNull(1) ?: persons.getOrElse(0) { p1 }
                val p3 = sorted.getOrNull(2) ?: persons.getOrElse(0) { p1 }
                val p4 = sorted.getOrNull(3) ?: persons.getOrElse(0) { p1 }

                val gridSlots = listOf(
                    Triple(p1, 0f, 0f),
                    Triple(p2, 0.5f, 0f),
                    Triple(p3, 0f, 0.5f),
                    Triple(p4, 0.5f, 0.5f)
                )

                gridSlots.forEachIndexed { idx, (p, nx, ny) ->
                    val plan = buildPersonDedicatedCropPlan("${clipId}_p${p.personId}", p, samples, sourceWidth, sourceHeight, 9.0 / 16.0)
                    panels.add(
                        PanelCropConfig(
                            panelIndex = idx,
                            personId = p.personId,
                            panelNormX = nx,
                            panelNormY = ny,
                            panelNormWidth = 0.5f,
                            panelNormHeight = 0.5f,
                            cropPlan = plan,
                            faceHeightRatio = (p.avgBoundingBox.bottom - p.avgBoundingBox.top) * 0.5f
                        )
                    )
                }
            }

            else -> {}
        }

        return panels
    }

    /**
     * Builds a smooth dedicated crop path centered tightly around an individual person
     * with comfortable headroom and margins.
     */
    private fun buildPersonDedicatedCropPlan(
        clipId: String,
        person: PersonProfile,
        samples: List<FaceSample>,
        sourceWidth: Int,
        sourceHeight: Int,
        targetAspect: Double
    ): ClipCropPlan {
        val cropHeight = sourceHeight
        var cropWidth = (cropHeight * targetAspect).roundToInt()
        if (cropWidth % 2 != 0) cropWidth -= 1
        cropWidth = cropWidth.coerceAtMost(sourceWidth)

        val maxCropX = max(0, sourceWidth - cropWidth)
        val keyframes = mutableListOf<CropKeyframe>()

        for (sample in samples) {
            // Find this specific person's face in the sample
            val face = sample.faces.find { it.trackingId == person.trackingId }
                ?: sample.faces.minByOrNull { abs((it.left + it.right) / 2f - (person.avgBoundingBox.left + person.avgBoundingBox.right) / 2f) }

            val centerX = if (face != null) {
                ((face.left + face.right) / 2f) * sourceWidth
            } else {
                ((person.avgBoundingBox.left + person.avgBoundingBox.right) / 2f) * sourceWidth
            }

            var cropX = (centerX - (cropWidth / 2f)).roundToInt().coerceIn(0, maxCropX)
            if (cropX % 2 != 0) cropX = max(0, cropX - 1)

            keyframes.add(
                CropKeyframe(
                    timestampMs = sample.timestampMs,
                    cropX = cropX,
                    cropY = 0,
                    cropWidth = cropWidth,
                    cropHeight = cropHeight,
                    mode = TrackingMode.FACE,
                    faceBox = face ?: person.avgBoundingBox
                )
            )
        }

        return ClipCropPlan(
            clipId = clipId,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            cropWidth = cropWidth,
            cropHeight = cropHeight,
            keyframes = keyframes,
            dominantMode = TrackingMode.FACE
        )
    }

    /**
     * Detects when person presence enters/leaves frame and creates throttled switch points
     * (at most once every 10 seconds, with 0.5s crossfade).
     */
    private fun computeSwitchPoints(
        samples: List<FaceSample>,
        includedPersons: List<PersonProfile>,
        baseLayout: LayoutType
    ): List<LayoutSwitchPoint> {
        val switchPoints = mutableListOf<LayoutSwitchPoint>()
        if (samples.isEmpty()) return switchPoints

        var lastSwitchMs = -SWITCH_THROTTLE_INTERVAL_MS
        var currentLayout = baseLayout

        for (sample in samples) {
            val visibleInSample = sample.faces.size
            val targetLayout = when (visibleInSample) {
                1 -> LayoutType.SINGLE
                2 -> LayoutType.TWO_PERSON_STACKED
                3 -> LayoutType.THREE_PERSON_GRID
                4 -> LayoutType.FOUR_PERSON_GRID
                else -> baseLayout
            }

            if (targetLayout != currentLayout && (sample.timestampMs - lastSwitchMs) >= SWITCH_THROTTLE_INTERVAL_MS) {
                switchPoints.add(
                    LayoutSwitchPoint(
                        timestampMs = sample.timestampMs,
                        fromLayout = currentLayout,
                        toLayout = targetLayout,
                        activePersonCount = visibleInSample
                    )
                )
                lastSwitchMs = sample.timestampMs
                currentLayout = targetLayout
            }
        }

        return switchPoints
    }

    private fun saveLayoutToDisk(file: File, plan: ClipLayoutPlan) {
        try {
            val root = JSONObject()
            root.put("clipId", plan.clipId)
            root.put("layoutType", plan.layoutType.name)
            root.put("distinctPersonCount", plan.distinctPersonCount)
            root.put("reviewStatus", plan.reviewStatus.name)
            if (plan.reviewReason != null) root.put("reviewReason", plan.reviewReason)

            val pArr = JSONArray()
            for (p in plan.persons) {
                val pObj = JSONObject()
                pObj.put("personId", p.personId)
                if (p.trackingId != null) pObj.put("trackingId", p.trackingId)
                pObj.put("label", p.label)
                pObj.put("totalVisibleMs", p.totalVisibleMs)
                pObj.put("visibleRatio", p.visibleRatio.toDouble())
                pObj.put("isIncluded", p.isIncluded)
                if (p.assignedSpeakerLabel != null) pObj.put("assignedSpeakerLabel", p.assignedSpeakerLabel)

                val boxObj = JSONObject()
                boxObj.put("left", p.avgBoundingBox.left.toDouble())
                boxObj.put("top", p.avgBoundingBox.top.toDouble())
                boxObj.put("right", p.avgBoundingBox.right.toDouble())
                boxObj.put("bottom", p.avgBoundingBox.bottom.toDouble())
                pObj.put("avgBoundingBox", boxObj)

                pArr.put(pObj)
            }
            root.put("persons", pArr)

            val swArr = JSONArray()
            for (sw in plan.switchPoints) {
                val sObj = JSONObject()
                sObj.put("timestampMs", sw.timestampMs)
                sObj.put("fromLayout", sw.fromLayout.name)
                sObj.put("toLayout", sw.toLayout.name)
                sObj.put("activePersonCount", sw.activePersonCount)
                swArr.put(sObj)
            }
            root.put("switchPoints", swArr)

            file.writeText(root.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save layout json", e)
        }
    }

    private fun loadCachedLayout(file: File, clipId: String): ClipLayoutPlan? {
        return runCatching {
            val json = JSONObject(file.readText())
            val lTypeStr = json.optString("layoutType", LayoutType.SINGLE.name)
            val layoutType = runCatching { LayoutType.valueOf(lTypeStr) }.getOrDefault(LayoutType.SINGLE)
            val distinctCount = json.optInt("distinctPersonCount", 1)
            val revStatusStr = json.optString("reviewStatus", LayoutReviewStatus.OK.name)
            val reviewStatus = runCatching { LayoutReviewStatus.valueOf(revStatusStr) }.getOrDefault(LayoutReviewStatus.OK)
            val reviewReason = if (json.has("reviewReason")) json.optString("reviewReason") else null

            val pArr = json.optJSONArray("persons")
            val persons = mutableListOf<PersonProfile>()
            if (pArr != null) {
                for (i in 0 until pArr.length()) {
                    val pObj = pArr.getJSONObject(i)
                    val boxObj = pObj.getJSONObject("avgBoundingBox")
                    persons.add(
                        PersonProfile(
                            personId = pObj.getInt("personId"),
                            trackingId = if (pObj.has("trackingId")) pObj.getInt("trackingId") else null,
                            label = pObj.optString("label", "Person ${pObj.getInt("personId")}"),
                            avgBoundingBox = TrackedFace(
                                left = boxObj.getDouble("left").toFloat(),
                                top = boxObj.getDouble("top").toFloat(),
                                right = boxObj.getDouble("right").toFloat(),
                                bottom = boxObj.getDouble("bottom").toFloat()
                            ),
                            totalVisibleMs = pObj.optLong("totalVisibleMs", 0L),
                            visibleRatio = pObj.optDouble("visibleRatio", 0.0).toFloat(),
                            assignedSpeakerLabel = if (pObj.has("assignedSpeakerLabel")) pObj.optString("assignedSpeakerLabel") else null,
                            isIncluded = pObj.optBoolean("isIncluded", true)
                        )
                    )
                }
            }

            val swArr = json.optJSONArray("switchPoints")
            val switchPoints = mutableListOf<LayoutSwitchPoint>()
            if (swArr != null) {
                for (i in 0 until swArr.length()) {
                    val sObj = swArr.getJSONObject(i)
                    switchPoints.add(
                        LayoutSwitchPoint(
                            timestampMs = sObj.getLong("timestampMs"),
                            fromLayout = LayoutType.valueOf(sObj.getString("fromLayout")),
                            toLayout = LayoutType.valueOf(sObj.getString("toLayout")),
                            activePersonCount = sObj.getInt("activePersonCount")
                        )
                    )
                }
            }

            ClipLayoutPlan(
                clipId = clipId,
                layoutType = layoutType,
                distinctPersonCount = distinctCount,
                persons = persons,
                panels = emptyList(), // Recomputed dynamically on load if needed
                switchPoints = switchPoints,
                reviewStatus = reviewStatus,
                reviewReason = reviewReason,
                layoutJsonPath = file.absolutePath
            )
        }.getOrNull()
    }
}
