package com.clipgenius.ai.verification

// This class never calls AI. It only checks AI output against ground-truth transcript data. It may REJECT but never INVENT.

import com.clipgenius.ai.state.ClipCandidate
import com.clipgenius.ai.state.ProjectTranscript
import com.clipgenius.ai.state.SourceRange
import com.clipgenius.ai.state.TranscriptSegment
import com.clipgenius.ai.state.VerificationStatus
import com.clipgenius.ai.state.VerifiedClip
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Deterministic timestamp and quote verification engine for Clip Genius AI.
 * Validates AI-generated clip candidates against ground-truth transcript word timings.
 */
class TimestampVerifier {

    data class GroundTruthWord(
        val originalWord: String,
        val normalizedWord: String,
        val startMs: Long,
        val endMs: Long,
        val segmentIndex: Int,
        val wordIndex: Int
    )

    data class MatchResult(
        val matchedWordCount: Int,
        val totalCandidateWords: Int,
        val overlapRatio: Float,
        val actualStartMs: Long,
        val actualEndMs: Long,
        val firstMatchedIndex: Int,
        val lastMatchedIndex: Int
    )

    /**
     * Extracts an ordered sequence of ground-truth words with precise millisecond timestamps
     * from the merged transcript segments.
     */
    fun extractGroundTruthWords(transcript: ProjectTranscript?): List<GroundTruthWord> {
        if (transcript == null || transcript.segments.isEmpty()) return emptyList()

        val result = mutableListOf<GroundTruthWord>()
        var globalWordIndex = 0

        transcript.segments.forEachIndexed { segIdx, segment ->
            if (segment.words.isNotEmpty()) {
                // Use Deepgram word-level timestamps when available
                segment.words.forEach { word ->
                    val norm = normalizeWord(word.word)
                    if (norm.isNotEmpty()) {
                        result.add(
                            GroundTruthWord(
                                originalWord = word.word,
                                normalizedWord = norm,
                                startMs = word.startMs,
                                endMs = word.endMs,
                                segmentIndex = segIdx,
                                wordIndex = globalWordIndex++
                            )
                        )
                    }
                }
            } else {
                // Fallback: interpolate evenly across segment duration
                val rawWords = segment.text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
                if (rawWords.isNotEmpty()) {
                    val duration = max(0L, segment.endMs - segment.startMs)
                    val step = if (rawWords.size > 1) duration / rawWords.size else duration

                    rawWords.forEachIndexed { idx, w ->
                        val norm = normalizeWord(w)
                        if (norm.isNotEmpty()) {
                            val wStart = segment.startMs + (idx * step)
                            val wEnd = if (idx == rawWords.lastIndex) segment.endMs else wStart + step
                            result.add(
                                GroundTruthWord(
                                    originalWord = w,
                                    normalizedWord = norm,
                                    startMs = wStart,
                                    endMs = wEnd,
                                    segmentIndex = segIdx,
                                    wordIndex = globalWordIndex++
                                )
                            )
                        }
                    }
                }
            }
        }

        return result
    }

    /**
     * Normalizes a single word by lowercasing and stripping all punctuation.
     */
    fun normalizeWord(word: String): String {
        return word.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    /**
     * Splits text into a list of normalized words.
     */
    fun normalizeToWords(text: String): List<String> {
        return text.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotBlank() }
    }

    /**
     * Verifies all clip candidates against ground truth transcript and video constraints.
     */
    fun verifyAll(
        candidates: List<ClipCandidate>,
        transcript: ProjectTranscript?,
        videoDurationMs: Long,
        minLengthSeconds: Int = 60
    ): List<VerifiedClip> {
        val groundTruthWords = extractGroundTruthWords(transcript)
        return candidates.map { candidate ->
            verifyCandidate(
                candidate = candidate,
                groundTruthWords = groundTruthWords,
                videoDurationMs = videoDurationMs,
                minLengthSeconds = minLengthSeconds
            )
        }
    }

    /**
     * Deterministically checks a single candidate according to checks (a)-(e).
     */
    fun verifyCandidate(
        candidate: ClipCandidate,
        groundTruthWords: List<GroundTruthWord>,
        videoDurationMs: Long,
        minLengthSeconds: Int = 60
    ): VerifiedClip {
        val issues = mutableListOf<String>()
        var isInvalid = false
        var needsReview = false

        // --- CHECK A: TIME PARSING ---
        val parsedRanges = mutableListOf<SourceRange>()
        if (candidate.sourceRanges.isEmpty()) {
            issues.add("unparseable time: candidate has no source ranges")
            isInvalid = true
        } else {
            for (range in candidate.sourceRanges) {
                var sMs = range.startMs
                var eMs = range.endMs

                if (range.rawStart != null) {
                    val p = TimeUtils.parseToMs(range.rawStart)
                    if (p == null) {
                        issues.add("unparseable time: '${range.rawStart}' is not valid MM:SS")
                        isInvalid = true
                    } else {
                        sMs = p
                    }
                }

                if (range.rawEnd != null) {
                    val p = TimeUtils.parseToMs(range.rawEnd)
                    if (p == null) {
                        issues.add("unparseable time: '${range.rawEnd}' is not valid MM:SS")
                        isInvalid = true
                    } else {
                        eMs = p
                    }
                }

                if (sMs < 0 || eMs <= 0) {
                    issues.add("unparseable time: invalid negative or zero timestamp ($sMs - $eMs)")
                    isInvalid = true
                } else {
                    parsedRanges.add(SourceRange(sMs, eMs, range.rawStart, range.rawEnd))
                }
            }
        }

        var hookStart = candidate.hookStartMs
        var hookEnd = candidate.hookEndMs
        if (candidate.rawHookStart != null) {
            val p = TimeUtils.parseToMs(candidate.rawHookStart)
            if (p == null) {
                issues.add("unparseable time: hook start '${candidate.rawHookStart}' is invalid")
                isInvalid = true
            } else {
                hookStart = p
            }
        }
        if (candidate.rawHookEnd != null) {
            val p = TimeUtils.parseToMs(candidate.rawHookEnd)
            if (p == null) {
                issues.add("unparseable time: hook end '${candidate.rawHookEnd}' is invalid")
                isInvalid = true
            } else {
                hookEnd = p
            }
        }

        // --- CHECK B: START < END, VIDEO BOUNDS, AND DURATION ---
        if (parsedRanges.isNotEmpty()) {
            for (range in parsedRanges) {
                if (range.startMs >= range.endMs) {
                    issues.add("Start time (${TimeUtils.formatMs(range.startMs)}) must be earlier than end time (${TimeUtils.formatMs(range.endMs)})")
                    isInvalid = true
                }
                if (range.startMs < 0 || (videoDurationMs > 0 && range.endMs > videoDurationMs)) {
                    issues.add("Range ${TimeUtils.formatMs(range.startMs)}-${TimeUtils.formatMs(range.endMs)} is outside video duration (0..${TimeUtils.formatMs(videoDurationMs)})")
                    isInvalid = true
                }
            }

            val totalDurationMs = parsedRanges.sumOf { it.endMs - it.startMs }
            val minAllowedMs = max(0L, (minLengthSeconds - 5) * 1000L) // 5s tolerance
            if (totalDurationMs < minAllowedMs) {
                issues.add("Clip duration (${totalDurationMs / 1000}s) is shorter than minimum required (${minLengthSeconds}s with 5s tolerance)")
                isInvalid = true
            }
        }

        // --- CHECK C & D: TEXT-TO-TIME MAPPING & QUOTE VALIDATION ---
        val candidateWords = normalizeToWords(candidate.transcriptText)
        var actualFoundStartMs: Long? = null
        var actualFoundEndMs: Long? = null
        var matchConfidence = 0f

        if (candidateWords.isEmpty()) {
            issues.add("quote not found in transcript — AI may have invented it (empty quote)")
            isInvalid = true
        } else if (groundTruthWords.isEmpty()) {
            issues.add("quote not found in transcript — AI may have invented it (no transcript words)")
            isInvalid = true
        } else {
            val claimedStart = parsedRanges.firstOrNull()?.startMs ?: candidate.hookStartMs
            val claimedEnd = parsedRanges.lastOrNull()?.endMs ?: (claimedStart + 60000L)

            val matchResult = findBestMatchWindow(
                candidateWords = candidateWords,
                groundTruthWords = groundTruthWords,
                claimedStartMs = claimedStart
            )

            matchConfidence = matchResult.overlapRatio

            if (matchResult.overlapRatio < 0.70f) {
                val percentage = (matchResult.overlapRatio * 100).toInt()
                issues.add("quote not found in transcript — AI may have invented it (matched $percentage% of quoted words, minimum 70% required)")
                isInvalid = true
            } else {
                actualFoundStartMs = matchResult.actualStartMs
                actualFoundEndMs = matchResult.actualEndMs

                // Compare claimed range against actual found word position
                val startDiff = abs(claimedStart - matchResult.actualStartMs)
                val endDiff = abs(claimedEnd - matchResult.actualEndMs)

                if (startDiff > 10000L || endDiff > 10000L) {
                    needsReview = true
                    issues.add(
                        "Timing discrepancy > 10s: AI claimed ${TimeUtils.formatMs(claimedStart)}-${TimeUtils.formatMs(claimedEnd)}, " +
                                "but text actually found at ${TimeUtils.formatMs(matchResult.actualStartMs)}-${TimeUtils.formatMs(matchResult.actualEndMs)}"
                    )
                }
            }
        }

        // --- CHECK E: HOOK CHECK ---
        var verifiedHookStart = hookStart
        var verifiedHookEnd = hookEnd
        if (parsedRanges.isNotEmpty()) {
            val fallsInside = parsedRanges.any { range ->
                range.startMs <= verifiedHookStart && verifiedHookEnd <= range.endMs
            }

            if (!fallsInside) {
                val nearestRange = parsedRanges.minByOrNull { range ->
                    val distStart = abs(range.startMs - verifiedHookStart)
                    val distEnd = abs(range.endMs - verifiedHookEnd)
                    min(distStart, distEnd)
                } ?: parsedRanges.first()

                val clampedStart = verifiedHookStart.coerceIn(
                    nearestRange.startMs,
                    max(nearestRange.startMs, nearestRange.endMs - 1000L)
                )
                val clampedEnd = verifiedHookEnd.coerceIn(
                    clampedStart + 500L,
                    nearestRange.endMs
                )

                issues.add(
                    "Hook timing (${TimeUtils.formatMs(verifiedHookStart)}-${TimeUtils.formatMs(verifiedHookEnd)}) " +
                            "fell outside clip range; clamped to ${TimeUtils.formatMs(clampedStart)}-${TimeUtils.formatMs(clampedEnd)}"
                )

                verifiedHookStart = clampedStart
                verifiedHookEnd = clampedEnd
            }
        }

        val status = when {
            isInvalid -> VerificationStatus.INVALID
            needsReview -> VerificationStatus.NEEDS_REVIEW
            else -> VerificationStatus.VERIFIED
        }

        return VerifiedClip(
            clipId = candidate.clipId,
            verifiedRanges = parsedRanges,
            verifiedHookStartMs = verifiedHookStart,
            verifiedHookEndMs = verifiedHookEnd,
            status = status,
            issues = issues,
            originalCandidate = candidate,
            isManuallyEdited = false,
            actualFoundStartMs = actualFoundStartMs,
            actualFoundEndMs = actualFoundEndMs,
            matchConfidence = matchConfidence,
            isIncludedInExport = candidate.isIncludedInExport && status != VerificationStatus.INVALID
        )
    }

    /**
     * Deterministically finds the window in groundTruthWords that best matches candidateWords.
     * Evaluates sequence overlap and selects highest overlap ratio, breaking ties by proximity to claimedStartMs.
     */
    fun findBestMatchWindow(
        candidateWords: List<String>,
        groundTruthWords: List<GroundTruthWord>,
        claimedStartMs: Long
    ): MatchResult {
        if (candidateWords.isEmpty() || groundTruthWords.isEmpty()) {
            return MatchResult(0, candidateWords.size, 0f, 0L, 0L, 0, 0)
        }

        val m = candidateWords.size
        var bestMatches = 0
        var bestStartIdx = 0
        var bestEndIdx = 0
        var bestDistanceToClaimed = Long.MAX_VALUE

        // Build index of potential start points (where first 3 words of candidate match)
        val firstCandidateWord = candidateWords[0]
        val secondCandidateWord = candidateWords.getOrNull(1)

        val potentialStarts = mutableListOf<Int>()
        for (i in groundTruthWords.indices) {
            val norm = groundTruthWords[i].normalizedWord
            if (norm == firstCandidateWord || (secondCandidateWord != null && norm == secondCandidateWord)) {
                potentialStarts.add(i)
            }
        }

        // If no direct head matches, check all words or anchor words of length >= 4
        if (potentialStarts.isEmpty()) {
            val candidateSet = candidateWords.filter { it.length >= 4 }.toSet()
            for (i in groundTruthWords.indices) {
                if (groundTruthWords[i].normalizedWord in candidateSet) {
                    potentialStarts.add(max(0, i - (m / 2)))
                }
            }
        }

        // Fallback: check every (m / 2) positions if still empty
        if (potentialStarts.isEmpty()) {
            for (i in groundTruthWords.indices step max(1, m / 2)) {
                potentialStarts.add(i)
            }
        }

        val evaluatedStarts = potentialStarts.distinct()

        for (start in evaluatedStarts) {
            val maxWindowWords = min(groundTruthWords.size, start + (m * 2) + 15)
            var cIdx = 0
            var matchedInWindow = 0
            var lastMatchedGroundIdx = start

            for (gIdx in start until maxWindowWords) {
                if (cIdx < m && groundTruthWords[gIdx].normalizedWord == candidateWords[cIdx]) {
                    matchedInWindow++
                    lastMatchedGroundIdx = gIdx
                    cIdx++
                } else if (cIdx + 1 < m && groundTruthWords[gIdx].normalizedWord == candidateWords[cIdx + 1]) {
                    // Slight skip in transcript (filler word skipped by AI)
                    matchedInWindow++
                    lastMatchedGroundIdx = gIdx
                    cIdx += 2
                }
            }

            val windowStartMs = groundTruthWords[start].startMs
            val distance = abs(windowStartMs - claimedStartMs)

            if (matchedInWindow > bestMatches ||
                (matchedInWindow == bestMatches && distance < bestDistanceToClaimed)
            ) {
                bestMatches = matchedInWindow
                bestStartIdx = start
                bestEndIdx = lastMatchedGroundIdx
                bestDistanceToClaimed = distance
            }
        }

        val overlap = bestMatches.toFloat() / m
        val foundStartMs = groundTruthWords.getOrNull(bestStartIdx)?.startMs ?: 0L
        val foundEndMs = groundTruthWords.getOrNull(bestEndIdx)?.endMs ?: foundStartMs

        return MatchResult(
            matchedWordCount = bestMatches,
            totalCandidateWords = m,
            overlapRatio = overlap,
            actualStartMs = foundStartMs,
            actualEndMs = foundEndMs,
            firstMatchedIndex = bestStartIdx,
            lastMatchedIndex = bestEndIdx
        )
    }

    /**
     * Verifies manual user edits to a clip's timestamps.
     * Executes checks (a) and (b) only (skips text mapping, records 'manually set by user' in issues).
     */
    fun verifyManualEdit(
        originalClip: VerifiedClip,
        newStartMs: Long,
        newEndMs: Long,
        newHookStartMs: Long,
        newHookEndMs: Long,
        videoDurationMs: Long,
        minLengthSeconds: Int = 60
    ): VerifiedClip {
        val issues = mutableListOf<String>()
        var isInvalid = false

        // Check A & B: Range integrity
        if (newStartMs < 0 || newEndMs <= 0) {
            issues.add("unparseable time: negative or zero timestamp")
            isInvalid = true
        }

        if (newStartMs >= newEndMs) {
            issues.add("Start time (${TimeUtils.formatMs(newStartMs)}) must be earlier than end time (${TimeUtils.formatMs(newEndMs)})")
            isInvalid = true
        }

        if (newStartMs < 0 || (videoDurationMs > 0 && newEndMs > videoDurationMs)) {
            issues.add("Range ${TimeUtils.formatMs(newStartMs)}-${TimeUtils.formatMs(newEndMs)} is outside video duration (0..${TimeUtils.formatMs(videoDurationMs)})")
            isInvalid = true
        }

        val totalDurationMs = newEndMs - newStartMs
        val minAllowedMs = max(0L, (minLengthSeconds - 5) * 1000L)
        if (totalDurationMs < minAllowedMs) {
            issues.add("Clip duration (${totalDurationMs / 1000}s) is shorter than minimum required (${minLengthSeconds}s with 5s tolerance)")
            isInvalid = true
        }

        // Clamp hook if needed
        val clampedHookStart = newHookStartMs.coerceIn(newStartMs, max(newStartMs, newEndMs - 1000L))
        val clampedHookEnd = newHookEndMs.coerceIn(clampedHookStart + 500L, newEndMs)

        if (!isInvalid) {
            issues.add("Manually set by user to ${TimeUtils.formatMs(newStartMs)} - ${TimeUtils.formatMs(newEndMs)}")
        }

        val status = if (isInvalid) VerificationStatus.INVALID else VerificationStatus.VERIFIED

        return originalClip.copy(
            verifiedRanges = listOf(SourceRange(newStartMs, newEndMs)),
            verifiedHookStartMs = clampedHookStart,
            verifiedHookEndMs = clampedHookEnd,
            status = status,
            issues = issues,
            isManuallyEdited = true,
            isIncludedInExport = !isInvalid
        )
    }

    /**
     * Resolves a NEEDS_REVIEW clip by accepting the actually-found timestamp.
     */
    fun acceptFoundTime(clip: VerifiedClip): VerifiedClip {
        val foundStart = clip.actualFoundStartMs ?: clip.verifiedRanges.firstOrNull()?.startMs ?: 0L
        val foundEnd = clip.actualFoundEndMs ?: clip.verifiedRanges.lastOrNull()?.endMs ?: (foundStart + 60000L)

        val updatedRanges = listOf(SourceRange(foundStart, foundEnd))
        val clampedHookStart = clip.verifiedHookStartMs.coerceIn(foundStart, max(foundStart, foundEnd - 1000L))
        val clampedHookEnd = clip.verifiedHookEndMs.coerceIn(clampedHookStart + 500L, foundEnd)

        val updatedIssues = clip.issues.filterNot { it.contains("Timing discrepancy", ignoreCase = true) } +
                "Accepted actually-found timestamp (${TimeUtils.formatMs(foundStart)} - ${TimeUtils.formatMs(foundEnd)})"

        return clip.copy(
            verifiedRanges = updatedRanges,
            verifiedHookStartMs = clampedHookStart,
            verifiedHookEndMs = clampedHookEnd,
            status = VerificationStatus.VERIFIED,
            issues = updatedIssues,
            isIncludedInExport = true
        )
    }
}
