package com.clipgenius.ai.data

import androidx.room.TypeConverter
import com.clipgenius.ai.state.AudioChunk
import com.clipgenius.ai.state.AudioChunkManifest
import com.clipgenius.ai.state.ChunkStatus
import com.clipgenius.ai.state.SourceMedia
import org.json.JSONArray
import org.json.JSONObject

class ProjectTypeConverters {

    @TypeConverter
    fun fromStagesMap(stages: Map<String, String>?): String {
        if (stages == null) return "{}"
        val json = JSONObject()
        for ((key, value) in stages) {
            json.put(key, value)
        }
        return json.toString()
    }

    @TypeConverter
    fun toStagesMap(jsonString: String?): Map<String, String> {
        if (jsonString.isNullOrEmpty()) return emptyMap()
        val map = mutableMapOf<String, String>()
        runCatching {
            val json = JSONObject(jsonString)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = json.getString(key)
            }
        }
        return map
    }

    @TypeConverter
    fun fromSourceMedia(media: SourceMedia?): String? {
        if (media == null) return null
        val json = JSONObject()
        json.put("path", media.path)
        json.put("fileName", media.fileName)
        json.put("durationMs", media.durationMs)
        json.put("width", media.width)
        json.put("height", media.height)
        json.put("rotation", media.rotation)
        json.put("frameRate", media.frameRate.toDouble())
        json.put("hasAudio", media.hasAudio)
        json.put("codec", media.codec)
        json.put("audioCodec", media.audioCodec)
        json.put("fileSizeBytes", media.fileSizeBytes)
        return json.toString()
    }

    @TypeConverter
    fun toSourceMedia(jsonString: String?): SourceMedia? {
        if (jsonString.isNullOrEmpty()) return null
        return runCatching {
            val json = JSONObject(jsonString)
            SourceMedia(
                path = json.optString("path", ""),
                fileName = json.optString("fileName", ""),
                durationMs = json.optLong("durationMs", 0L),
                width = json.optInt("width", 0),
                height = json.optInt("height", 0),
                rotation = json.optInt("rotation", 0),
                frameRate = json.optDouble("frameRate", 0.0).toFloat(),
                hasAudio = json.optBoolean("hasAudio", false),
                codec = json.optString("codec", ""),
                audioCodec = json.optString("audioCodec", ""),
                fileSizeBytes = json.optLong("fileSizeBytes", 0L)
            )
        }.getOrNull()
    }

    @TypeConverter
    fun fromAudioChunkManifest(manifest: AudioChunkManifest?): String? {
        if (manifest == null) return null
        val json = JSONObject()
        json.put("fullAudioPath", manifest.fullAudioPath)
        json.put("totalDurationMs", manifest.totalDurationMs)
        json.put("isExtractionComplete", manifest.isExtractionComplete)

        val chunksArray = JSONArray()
        for (chunk in manifest.chunks) {
            val chunkObj = JSONObject()
            chunkObj.put("index", chunk.index)
            chunkObj.put("filePath", chunk.filePath)
            chunkObj.put("startMs", chunk.startMs)
            chunkObj.put("endMs", chunk.endMs)
            chunkObj.put("overlapMs", chunk.overlapMs)
            chunkObj.put("status", chunk.status.name)
            if (chunk.errorMessage != null) {
                chunkObj.put("errorMessage", chunk.errorMessage)
            }
            chunksArray.put(chunkObj)
        }
        json.put("chunks", chunksArray)
        return json.toString()
    }

    @TypeConverter
    fun toAudioChunkManifest(jsonString: String?): AudioChunkManifest? {
        if (jsonString.isNullOrEmpty()) return null
        return runCatching {
            val json = JSONObject(jsonString)
            val fullAudioPath = json.optString("fullAudioPath", "")
            val totalDurationMs = json.optLong("totalDurationMs", 0L)
            val isExtractionComplete = json.optBoolean("isExtractionComplete", false)

            val chunks = mutableListOf<AudioChunk>()
            val chunksArray = json.optJSONArray("chunks")
            if (chunksArray != null) {
                for (i in 0 until chunksArray.length()) {
                    val obj = chunksArray.getJSONObject(i)
                    chunks.add(
                        AudioChunk(
                            index = obj.optInt("index", i),
                            filePath = obj.optString("filePath", ""),
                            startMs = obj.optLong("startMs", 0L),
                            endMs = obj.optLong("endMs", 0L),
                            overlapMs = obj.optLong("overlapMs", 0L),
                            status = runCatching { ChunkStatus.valueOf(obj.optString("status", "QUEUED")) }.getOrDefault(ChunkStatus.QUEUED),
                            errorMessage = if (obj.has("errorMessage")) obj.optString("errorMessage") else null
                        )
                    )
                }
            }
            AudioChunkManifest(
                fullAudioPath = fullAudioPath,
                totalDurationMs = totalDurationMs,
                chunks = chunks,
                isExtractionComplete = isExtractionComplete
            )
        }.getOrNull()
    }

    @TypeConverter
    fun fromProjectTranscript(transcript: com.clipgenius.ai.state.ProjectTranscript?): String? {
        if (transcript == null) return null
        val json = JSONObject()
        json.put("isFallbackGemini", transcript.isFallbackGemini)
        json.put("totalWords", transcript.totalWords)
        json.put("totalSpeakers", transcript.totalSpeakers)
        json.put("srtPath", transcript.srtPath)
        json.put("jsonPath", transcript.jsonPath)
        json.put("completedAt", transcript.completedAt)

        val segArray = JSONArray()
        for (seg in transcript.segments) {
            val segObj = JSONObject()
            segObj.put("id", seg.id)
            segObj.put("startMs", seg.startMs)
            segObj.put("endMs", seg.endMs)
            segObj.put("text", seg.text)
            segObj.put("isEdited", seg.isEdited)
            if (seg.speakerLabel != null) {
                segObj.put("speakerLabel", seg.speakerLabel)
            }

            val wordsArray = JSONArray()
            for (w in seg.words) {
                val wObj = JSONObject()
                wObj.put("word", w.word)
                wObj.put("startMs", w.startMs)
                wObj.put("endMs", w.endMs)
                if (w.speaker != null) wObj.put("speaker", w.speaker)
                if (w.confidence != null) wObj.put("confidence", w.confidence.toDouble())
                wordsArray.put(wObj)
            }
            segObj.put("words", wordsArray)
            segArray.put(segObj)
        }
        json.put("segments", segArray)
        return json.toString()
    }

    @TypeConverter
    fun toProjectTranscript(jsonString: String?): com.clipgenius.ai.state.ProjectTranscript? {
        if (jsonString.isNullOrEmpty()) return null
        return runCatching {
            val json = JSONObject(jsonString)
            val isFallbackGemini = json.optBoolean("isFallbackGemini", false)
            val totalWords = json.optInt("totalWords", 0)
            val totalSpeakers = json.optInt("totalSpeakers", 0)
            val srtPath = json.optString("srtPath", "")
            val jsonPath = json.optString("jsonPath", "")
            val completedAt = json.optLong("completedAt", System.currentTimeMillis())

            val segments = mutableListOf<com.clipgenius.ai.state.TranscriptSegment>()
            val segArray = json.optJSONArray("segments")
            if (segArray != null) {
                for (i in 0 until segArray.length()) {
                    val segObj = segArray.getJSONObject(i)
                    val wordsList = mutableListOf<com.clipgenius.ai.state.TranscriptWord>()
                    val wordsArr = segObj.optJSONArray("words")
                    if (wordsArr != null) {
                        for (j in 0 until wordsArr.length()) {
                            val wObj = wordsArr.getJSONObject(j)
                            wordsList.add(
                                com.clipgenius.ai.state.TranscriptWord(
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
                        com.clipgenius.ai.state.TranscriptSegment(
                            id = segObj.optString("id", java.util.UUID.randomUUID().toString()),
                            startMs = segObj.optLong("startMs", 0L),
                            endMs = segObj.optLong("endMs", 0L),
                            text = segObj.optString("text", ""),
                            speakerLabel = if (segObj.has("speakerLabel")) segObj.optString("speakerLabel") else null,
                            words = wordsList,
                            isEdited = segObj.optBoolean("isEdited", false)
                        )
                    )
                }
            }

            com.clipgenius.ai.state.ProjectTranscript(
                segments = segments,
                isFallbackGemini = isFallbackGemini,
                totalWords = if (totalWords > 0) totalWords else segments.sumOf { it.words.size },
                totalSpeakers = totalSpeakers,
                srtPath = srtPath,
                jsonPath = jsonPath,
                completedAt = completedAt
            )
        }.getOrNull()
    }

    @androidx.room.TypeConverter
    fun fromClipCandidates(candidates: List<com.clipgenius.ai.state.ClipCandidate>?): String? {
        if (candidates.isNullOrEmpty()) return null
        return try {
            val array = JSONArray()
            for (candidate in candidates) {
                val obj = JSONObject()
                obj.put("clipId", candidate.clipId)
                obj.put("hookStartMs", candidate.hookStartMs)
                obj.put("hookEndMs", candidate.hookEndMs)
                obj.put("transcriptText", candidate.transcriptText)
                obj.put("score", candidate.score.toDouble())
                obj.put("title", candidate.title)
                obj.put("hookSentence", candidate.hookSentence)
                obj.put("viralityReason", candidate.viralityReason)
                obj.put("caption", candidate.caption)
                obj.put("isIncludedInExport", candidate.isIncludedInExport)

                val tagsArr = JSONArray()
                candidate.hashtags.forEach { tagsArr.put(it) }
                obj.put("hashtags", tagsArr)

                val rangesArr = JSONArray()
                for (range in candidate.sourceRanges) {
                    val rObj = JSONObject()
                    rObj.put("startMs", range.startMs)
                    rObj.put("endMs", range.endMs)
                    rangesArr.put(rObj)
                }
                obj.put("sourceRanges", rangesArr)

                array.put(obj)
            }
            array.toString()
        } catch (e: Exception) {
            null
        }
    }

    @androidx.room.TypeConverter
    fun toClipCandidates(json: String?): List<com.clipgenius.ai.state.ClipCandidate> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<com.clipgenius.ai.state.ClipCandidate>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val ranges = mutableListOf<com.clipgenius.ai.state.SourceRange>()
                if (obj.has("sourceRanges")) {
                    val rArr = obj.getJSONArray("sourceRanges")
                    for (j in 0 until rArr.length()) {
                        val rObj = rArr.getJSONObject(j)
                        ranges.add(
                            com.clipgenius.ai.state.SourceRange(
                                startMs = rObj.optLong("startMs", 0L),
                                endMs = rObj.optLong("endMs", 0L)
                            )
                        )
                    }
                }

                val tags = mutableListOf<String>()
                if (obj.has("hashtags")) {
                    val tArr = obj.getJSONArray("hashtags")
                    for (j in 0 until tArr.length()) {
                        tags.add(tArr.getString(j))
                    }
                }

                list.add(
                    com.clipgenius.ai.state.ClipCandidate(
                        clipId = obj.optString("clipId", java.util.UUID.randomUUID().toString()),
                        sourceRanges = ranges,
                        hookStartMs = obj.optLong("hookStartMs", 0L),
                        hookEndMs = obj.optLong("hookEndMs", 0L),
                        rawHookStart = if (obj.has("rawHookStart")) obj.optString("rawHookStart") else null,
                        rawHookEnd = if (obj.has("rawHookEnd")) obj.optString("rawHookEnd") else null,
                        transcriptText = obj.optString("transcriptText", ""),
                        score = obj.optDouble("score", 0.0).toFloat(),
                        title = obj.optString("title", ""),
                        hookSentence = obj.optString("hookSentence", ""),
                        viralityReason = obj.optString("viralityReason", ""),
                        caption = obj.optString("caption", ""),
                        hashtags = tags,
                        isIncludedInExport = obj.optBoolean("isIncludedInExport", true)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    @androidx.room.TypeConverter
    fun fromVerifiedClips(list: List<com.clipgenius.ai.state.VerifiedClip>?): String? {
        if (list.isNullOrEmpty()) return null
        return try {
            val array = JSONArray()
            for (clip in list) {
                val obj = JSONObject()
                obj.put("clipId", clip.clipId)

                val rangesArr = JSONArray()
                for (r in clip.verifiedRanges) {
                    val rObj = JSONObject()
                    rObj.put("startMs", r.startMs)
                    rObj.put("endMs", r.endMs)
                    if (r.rawStart != null) rObj.put("rawStart", r.rawStart)
                    if (r.rawEnd != null) rObj.put("rawEnd", r.rawEnd)
                    rangesArr.put(rObj)
                }
                obj.put("verifiedRanges", rangesArr)
                obj.put("verifiedHookStartMs", clip.verifiedHookStartMs)
                obj.put("verifiedHookEndMs", clip.verifiedHookEndMs)
                obj.put("status", clip.status.name)

                val issuesArr = JSONArray()
                for (issue in clip.issues) {
                    issuesArr.put(issue)
                }
                obj.put("issues", issuesArr)

                // Original Candidate
                val cand = clip.originalCandidate
                val candObj = JSONObject()
                candObj.put("clipId", cand.clipId)
                val candRanges = JSONArray()
                for (cr in cand.sourceRanges) {
                    val crObj = JSONObject()
                    crObj.put("startMs", cr.startMs)
                    crObj.put("endMs", cr.endMs)
                    if (cr.rawStart != null) crObj.put("rawStart", cr.rawStart)
                    if (cr.rawEnd != null) crObj.put("rawEnd", cr.rawEnd)
                    candRanges.put(crObj)
                }
                candObj.put("sourceRanges", candRanges)
                candObj.put("hookStartMs", cand.hookStartMs)
                candObj.put("hookEndMs", cand.hookEndMs)
                if (cand.rawHookStart != null) candObj.put("rawHookStart", cand.rawHookStart)
                if (cand.rawHookEnd != null) candObj.put("rawHookEnd", cand.rawHookEnd)
                candObj.put("transcriptText", cand.transcriptText)
                candObj.put("score", cand.score.toDouble())
                candObj.put("title", cand.title)
                candObj.put("hookSentence", cand.hookSentence)
                candObj.put("viralityReason", cand.viralityReason)
                candObj.put("caption", cand.caption)
                val candTags = JSONArray()
                for (tag in cand.hashtags) {
                    candTags.put(tag)
                }
                candObj.put("hashtags", candTags)
                candObj.put("isIncludedInExport", cand.isIncludedInExport)
                obj.put("originalCandidate", candObj)

                obj.put("isManuallyEdited", clip.isManuallyEdited)
                if (clip.actualFoundStartMs != null) obj.put("actualFoundStartMs", clip.actualFoundStartMs)
                if (clip.actualFoundEndMs != null) obj.put("actualFoundEndMs", clip.actualFoundEndMs)
                obj.put("matchConfidence", clip.matchConfidence.toDouble())
                obj.put("isIncludedInExport", clip.isIncludedInExport)

                // Phase 8 Cutting & Draft Metadata
                obj.put("cutStatus", clip.cutStatus.name)
                if (clip.draftVideoPath != null) obj.put("draftVideoPath", clip.draftVideoPath)
                obj.put("cutProgress", clip.cutProgress.toDouble())
                if (clip.cutErrorMessage != null) obj.put("cutErrorMessage", clip.cutErrorMessage)
                obj.put("qcPassed", clip.qcPassed)
                if (clip.qcDetails != null) obj.put("qcDetails", clip.qcDetails)
                if (clip.cutDurationMs != null) obj.put("cutDurationMs", clip.cutDurationMs)
                if (clip.cutFileSizeBytes != null) obj.put("cutFileSizeBytes", clip.cutFileSizeBytes)

                // Phase 9 Tracking & 9:16 Metadata
                obj.put("trackingStatus", clip.trackingStatus.name)
                if (clip.facetrackJsonPath != null) obj.put("facetrackJsonPath", clip.facetrackJsonPath)
                if (clip.verticalDraftPath != null) obj.put("verticalDraftPath", clip.verticalDraftPath)
                obj.put("trackingProgress", clip.trackingProgress.toDouble())
                if (clip.trackingErrorMessage != null) obj.put("trackingErrorMessage", clip.trackingErrorMessage)
                obj.put("verticalQcPassed", clip.verticalQcPassed)
                if (clip.verticalQcDetails != null) obj.put("verticalQcDetails", clip.verticalQcDetails)
                obj.put("manualCropOffsetX", clip.manualCropOffsetX.toDouble())
                obj.put("manualFaceMargin", clip.manualFaceMargin.toDouble())
                obj.put("dominantTrackingMode", clip.dominantTrackingMode.name)

                // Phase 10 Layout Metadata
                obj.put("layoutType", clip.layoutType.name)
                obj.put("effectiveLayoutType", clip.effectiveLayoutType.name)
                obj.put("distinctPersonCount", clip.distinctPersonCount)
                obj.put("layoutReviewStatus", clip.layoutReviewStatus.name)
                if (clip.layoutReviewReason != null) obj.put("layoutReviewReason", clip.layoutReviewReason)
                if (clip.layoutJsonPath != null) obj.put("layoutJsonPath", clip.layoutJsonPath)
                val pIdsArr = JSONArray()
                for (pId in clip.selectedPersonIds) {
                    pIdsArr.put(pId)
                }
                obj.put("selectedPersonIds", pIdsArr)

                // Phase 11 Captions Metadata
                if (clip.captionPresetId != null) obj.put("captionPresetId", clip.captionPresetId)
                obj.put("burnCaptions", clip.burnCaptions)
                obj.put("captionsStatus", clip.captionsStatus)
                if (clip.captionsJsonPath != null) obj.put("captionsJsonPath", clip.captionsJsonPath)

                // Phase 12 Effects & Final Export Metadata
                obj.put("selectedFilter", clip.selectedFilter)
                obj.put("isMirrored", clip.isMirrored)
                obj.put("manualReframeOffsetX", clip.manualReframeOffsetX.toDouble())
                obj.put("manualReframeZoom", clip.manualReframeZoom.toDouble())
                if (clip.finalExportPath != null) obj.put("finalExportPath", clip.finalExportPath)
                obj.put("finalExportStatus", clip.finalExportStatus.name)
                obj.put("finalExportProgress", clip.finalExportProgress.toDouble())
                if (clip.finalExportErrorMessage != null) obj.put("finalExportErrorMessage", clip.finalExportErrorMessage)
                obj.put("finalQcPassed", clip.finalQcPassed)
                val qcArr = JSONArray()
                for (qc in clip.finalQcDetails) qcArr.put(qc)
                obj.put("finalQcDetails", qcArr)
                if (clip.mediaStoreUri != null) obj.put("mediaStoreUri", clip.mediaStoreUri)
                if (clip.metadataTxtPath != null) obj.put("metadataTxtPath", clip.metadataTxtPath)

                array.put(obj)
            }
            array.toString()
        } catch (e: Exception) {
            null
        }
    }

    @androidx.room.TypeConverter
    fun toVerifiedClips(json: String?): List<com.clipgenius.ai.state.VerifiedClip> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<com.clipgenius.ai.state.VerifiedClip>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val ranges = mutableListOf<com.clipgenius.ai.state.SourceRange>()
                if (obj.has("verifiedRanges")) {
                    val rArr = obj.getJSONArray("verifiedRanges")
                    for (j in 0 until rArr.length()) {
                        val rObj = rArr.getJSONObject(j)
                        ranges.add(
                            com.clipgenius.ai.state.SourceRange(
                                startMs = rObj.optLong("startMs", 0L),
                                endMs = rObj.optLong("endMs", 0L),
                                rawStart = if (rObj.has("rawStart")) rObj.optString("rawStart") else null,
                                rawEnd = if (rObj.has("rawEnd")) rObj.optString("rawEnd") else null
                            )
                        )
                    }
                }

                val issues = mutableListOf<String>()
                if (obj.has("issues")) {
                    val issArr = obj.getJSONArray("issues")
                    for (j in 0 until issArr.length()) {
                        issues.add(issArr.getString(j))
                    }
                }

                val statusStr = obj.optString("status", com.clipgenius.ai.state.VerificationStatus.NEEDS_REVIEW.name)
                val status = try {
                    com.clipgenius.ai.state.VerificationStatus.valueOf(statusStr)
                } catch (e: Exception) {
                    com.clipgenius.ai.state.VerificationStatus.NEEDS_REVIEW
                }

                // Parse original candidate
                val candObj = obj.optJSONObject("originalCandidate")
                val originalCand = if (candObj != null) {
                    val cRanges = mutableListOf<com.clipgenius.ai.state.SourceRange>()
                    if (candObj.has("sourceRanges")) {
                        val crArr = candObj.getJSONArray("sourceRanges")
                        for (j in 0 until crArr.length()) {
                            val crObj = crArr.getJSONObject(j)
                            cRanges.add(
                                com.clipgenius.ai.state.SourceRange(
                                    startMs = crObj.optLong("startMs", 0L),
                                    endMs = crObj.optLong("endMs", 0L),
                                    rawStart = if (crObj.has("rawStart")) crObj.optString("rawStart") else null,
                                    rawEnd = if (crObj.has("rawEnd")) crObj.optString("rawEnd") else null
                                )
                            )
                        }
                    }
                    val cTags = mutableListOf<String>()
                    if (candObj.has("hashtags")) {
                        val ctArr = candObj.getJSONArray("hashtags")
                        for (j in 0 until ctArr.length()) {
                            cTags.add(ctArr.getString(j))
                        }
                    }
                    com.clipgenius.ai.state.ClipCandidate(
                        clipId = candObj.optString("clipId", ""),
                        sourceRanges = cRanges,
                        hookStartMs = candObj.optLong("hookStartMs", 0L),
                        hookEndMs = candObj.optLong("hookEndMs", 0L),
                        rawHookStart = if (candObj.has("rawHookStart")) candObj.optString("rawHookStart") else null,
                        rawHookEnd = if (candObj.has("rawHookEnd")) candObj.optString("rawHookEnd") else null,
                        transcriptText = candObj.optString("transcriptText", ""),
                        score = candObj.optDouble("score", 0.0).toFloat(),
                        title = candObj.optString("title", ""),
                        hookSentence = candObj.optString("hookSentence", ""),
                        viralityReason = candObj.optString("viralityReason", ""),
                        caption = candObj.optString("caption", ""),
                        hashtags = cTags,
                        isIncludedInExport = candObj.optBoolean("isIncludedInExport", true)
                    )
                } else {
                    com.clipgenius.ai.state.ClipCandidate(clipId = obj.optString("clipId", ""))
                }

                val cutStatusStr = obj.optString("cutStatus", com.clipgenius.ai.state.CutStatus.NOT_CUT.name)
                val cutStatus = try {
                    com.clipgenius.ai.state.CutStatus.valueOf(cutStatusStr)
                } catch (e: Exception) {
                    com.clipgenius.ai.state.CutStatus.NOT_CUT
                }

                list.add(
                    com.clipgenius.ai.state.VerifiedClip(
                        clipId = obj.optString("clipId", java.util.UUID.randomUUID().toString()),
                        verifiedRanges = ranges,
                        verifiedHookStartMs = obj.optLong("verifiedHookStartMs", 0L),
                        verifiedHookEndMs = obj.optLong("verifiedHookEndMs", 0L),
                        status = status,
                        issues = issues,
                        originalCandidate = originalCand,
                        isManuallyEdited = obj.optBoolean("isManuallyEdited", false),
                        actualFoundStartMs = if (obj.has("actualFoundStartMs")) obj.optLong("actualFoundStartMs") else null,
                        actualFoundEndMs = if (obj.has("actualFoundEndMs")) obj.optLong("actualFoundEndMs") else null,
                        matchConfidence = obj.optDouble("matchConfidence", 0.0).toFloat(),
                        isIncludedInExport = obj.optBoolean("isIncludedInExport", true),
                        cutStatus = cutStatus,
                        draftVideoPath = if (obj.has("draftVideoPath")) obj.optString("draftVideoPath") else null,
                        cutProgress = obj.optDouble("cutProgress", 0.0).toFloat(),
                        cutErrorMessage = if (obj.has("cutErrorMessage")) obj.optString("cutErrorMessage") else null,
                        qcPassed = obj.optBoolean("qcPassed", false),
                        qcDetails = if (obj.has("qcDetails")) obj.optString("qcDetails") else null,
                        cutDurationMs = if (obj.has("cutDurationMs")) obj.optLong("cutDurationMs") else null,
                        cutFileSizeBytes = if (obj.has("cutFileSizeBytes")) obj.optLong("cutFileSizeBytes") else null,
                        trackingStatus = runCatching {
                            com.clipgenius.ai.state.TrackingStatus.valueOf(
                                obj.optString("trackingStatus", com.clipgenius.ai.state.TrackingStatus.NOT_TRACKED.name)
                            )
                        }.getOrDefault(com.clipgenius.ai.state.TrackingStatus.NOT_TRACKED),
                        facetrackJsonPath = if (obj.has("facetrackJsonPath")) obj.optString("facetrackJsonPath") else null,
                        verticalDraftPath = if (obj.has("verticalDraftPath")) obj.optString("verticalDraftPath") else null,
                        trackingProgress = obj.optDouble("trackingProgress", 0.0).toFloat(),
                        trackingErrorMessage = if (obj.has("trackingErrorMessage")) obj.optString("trackingErrorMessage") else null,
                        verticalQcPassed = obj.optBoolean("verticalQcPassed", false),
                        verticalQcDetails = if (obj.has("verticalQcDetails")) obj.optString("verticalQcDetails") else null,
                        manualCropOffsetX = obj.optDouble("manualCropOffsetX", 0.0).toFloat(),
                        manualFaceMargin = obj.optDouble("manualFaceMargin", 1.0).toFloat(),
                        dominantTrackingMode = runCatching {
                            com.clipgenius.ai.state.TrackingMode.valueOf(
                                obj.optString("dominantTrackingMode", com.clipgenius.ai.state.TrackingMode.FACE.name)
                            )
                        }.getOrDefault(com.clipgenius.ai.state.TrackingMode.FACE),
                        layoutType = runCatching {
                            com.clipgenius.ai.state.LayoutType.valueOf(
                                obj.optString("layoutType", com.clipgenius.ai.state.LayoutType.AUTO.name)
                            )
                        }.getOrDefault(com.clipgenius.ai.state.LayoutType.AUTO),
                        effectiveLayoutType = runCatching {
                            com.clipgenius.ai.state.LayoutType.valueOf(
                                obj.optString("effectiveLayoutType", com.clipgenius.ai.state.LayoutType.SINGLE.name)
                            )
                        }.getOrDefault(com.clipgenius.ai.state.LayoutType.SINGLE),
                        distinctPersonCount = obj.optInt("distinctPersonCount", 1),
                        layoutReviewStatus = runCatching {
                            com.clipgenius.ai.state.LayoutReviewStatus.valueOf(
                                obj.optString("layoutReviewStatus", com.clipgenius.ai.state.LayoutReviewStatus.OK.name)
                            )
                        }.getOrDefault(com.clipgenius.ai.state.LayoutReviewStatus.OK),
                        layoutReviewReason = if (obj.has("layoutReviewReason")) obj.optString("layoutReviewReason") else null,
                        layoutJsonPath = if (obj.has("layoutJsonPath")) obj.optString("layoutJsonPath") else null,
                        selectedPersonIds = runCatching {
                            val pArr = obj.optJSONArray("selectedPersonIds")
                            val pList = mutableListOf<Int>()
                            if (pArr != null) {
                                for (k in 0 until pArr.length()) {
                                    pList.add(pArr.getInt(k))
                                }
                            }
                            pList
                        }.getOrDefault(emptyList()),
                        captionPresetId = if (obj.has("captionPresetId")) obj.optString("captionPresetId") else null,
                        burnCaptions = obj.optBoolean("burnCaptions", true),
                        captionsStatus = obj.optString("captionsStatus", "NOT_STARTED"),
                        captionsJsonPath = if (obj.has("captionsJsonPath")) obj.optString("captionsJsonPath") else null,
                        selectedFilter = obj.optString("selectedFilter", "None"),
                        isMirrored = obj.optBoolean("isMirrored", false),
                        manualReframeOffsetX = obj.optDouble("manualReframeOffsetX", 0.0).toFloat(),
                        manualReframeZoom = obj.optDouble("manualReframeZoom", 1.0).toFloat(),
                        finalExportPath = if (obj.has("finalExportPath")) obj.optString("finalExportPath") else null,
                        finalExportStatus = runCatching {
                            com.clipgenius.ai.state.RenderStatus.valueOf(obj.optString("finalExportStatus", com.clipgenius.ai.state.RenderStatus.PENDING.name))
                        }.getOrDefault(com.clipgenius.ai.state.RenderStatus.PENDING),
                        finalExportProgress = obj.optDouble("finalExportProgress", 0.0).toFloat(),
                        finalExportErrorMessage = if (obj.has("finalExportErrorMessage")) obj.optString("finalExportErrorMessage") else null,
                        finalQcPassed = obj.optBoolean("finalQcPassed", false),
                        finalQcDetails = runCatching {
                            val arr = obj.optJSONArray("finalQcDetails")
                            val list = mutableListOf<String>()
                            if (arr != null) {
                                for (k in 0 until arr.length()) list.add(arr.getString(k))
                            }
                            list
                        }.getOrDefault(emptyList()),
                        mediaStoreUri = if (obj.has("mediaStoreUri")) obj.optString("mediaStoreUri") else null,
                        metadataTxtPath = if (obj.has("metadataTxtPath")) obj.optString("metadataTxtPath") else null
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    @androidx.room.TypeConverter
    fun fromActivityLogs(logs: List<com.clipgenius.ai.state.ActivityLogEntry>): String? {
        if (logs.isEmpty()) return null
        return try {
            val array = JSONArray()
            for (log in logs) {
                val obj = JSONObject()
                obj.put("id", log.id)
                obj.put("timestamp", log.timestamp)
                obj.put("stage", log.stage)
                obj.put("operation", log.operation)
                obj.put("status", log.status)
                obj.put("details", log.details)
                array.put(obj)
            }
            array.toString()
        } catch (e: Exception) {
            null
        }
    }

    @androidx.room.TypeConverter
    fun toActivityLogs(json: String?): List<com.clipgenius.ai.state.ActivityLogEntry> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<com.clipgenius.ai.state.ActivityLogEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    com.clipgenius.ai.state.ActivityLogEntry(
                        id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        stage = obj.optString("stage", ""),
                        operation = obj.optString("operation", ""),
                        status = obj.optString("status", "INFO"),
                        details = obj.optString("details", "")
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    @androidx.room.TypeConverter
    fun fromProjectStage(stage: com.clipgenius.ai.state.ProjectStage?): String {
        return stage?.name ?: com.clipgenius.ai.state.ProjectStage.IMPORTED.name
    }

    @androidx.room.TypeConverter
    fun toProjectStage(value: String?): com.clipgenius.ai.state.ProjectStage {
        if (value.isNullOrBlank()) return com.clipgenius.ai.state.ProjectStage.IMPORTED
        return try {
            com.clipgenius.ai.state.ProjectStage.valueOf(value)
        } catch (e: Exception) {
            com.clipgenius.ai.state.ProjectStage.IMPORTED
        }
    }

    @androidx.room.TypeConverter
    fun fromProjectStatus(status: com.clipgenius.ai.state.ProjectStatus?): String {
        return status?.name ?: com.clipgenius.ai.state.ProjectStatus.ACTIVE.name
    }

    @androidx.room.TypeConverter
    fun toProjectStatus(value: String?): com.clipgenius.ai.state.ProjectStatus {
        if (value.isNullOrBlank()) return com.clipgenius.ai.state.ProjectStatus.ACTIVE
        return try {
            com.clipgenius.ai.state.ProjectStatus.valueOf(value)
        } catch (e: Exception) {
            com.clipgenius.ai.state.ProjectStatus.ACTIVE
        }
    }
}
