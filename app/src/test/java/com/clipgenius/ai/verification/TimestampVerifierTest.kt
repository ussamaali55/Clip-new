package com.clipgenius.ai.verification

import com.clipgenius.ai.state.ClipCandidate
import com.clipgenius.ai.state.ProjectTranscript
import com.clipgenius.ai.state.SourceRange
import com.clipgenius.ai.state.TranscriptSegment
import com.clipgenius.ai.state.TranscriptWord
import com.clipgenius.ai.state.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TimestampVerifierTest {

    private lateinit var verifier: TimestampVerifier

    @Before
    fun setup() {
        verifier = TimestampVerifier()
    }

    @Test
    fun testTimeUtils_parsingAndFormatting() {
        // Valid MM:SS
        assertEquals(0L, TimeUtils.parseToMs("00:00"))
        assertEquals(65000L, TimeUtils.parseToMs("01:05"))
        assertEquals(599000L, TimeUtils.parseToMs("09:59"))

        // Valid HH:MM:SS
        assertEquals(3600000L, TimeUtils.parseToMs("01:00:00"))
        assertEquals(3723000L, TimeUtils.parseToMs("01:02:03"))

        // Fractional seconds
        assertEquals(12340L, TimeUtils.parseToMs("00:12.34"))

        // Formatting
        assertEquals("01:05", TimeUtils.formatMs(65000L))
        assertEquals("01:02:03", TimeUtils.formatMs(3723000L))

        // Invalid formats - must return null and never crash
        assertNull(TimeUtils.parseToMs("invalid"))
        assertNull(TimeUtils.parseToMs("12"))
        assertNull(TimeUtils.parseToMs("-01:00"))
        assertNull(TimeUtils.parseToMs("12:65")) // invalid minute/second
        assertNull(TimeUtils.parseToMs(null))
    }

    @Test
    fun testVerifyCandidate_validClip_isVerified() {
        val transcript = createSampleTranscript()
        val videoDurationMs = 300000L // 5 mins

        val candidate = ClipCandidate(
            clipId = "c1",
            sourceRanges = listOf(SourceRange(10000L, 75000L, rawStart = "00:10", rawEnd = "01:15")),
            hookStartMs = 12000L,
            hookEndMs = 18000L,
            rawHookStart = "00:12",
            rawHookEnd = "00:18",
            transcriptText = "Welcome to the podcast. Today we talk about viral short form videos and creative storytelling that engages viewers immediately from the first second.",
            score = 88f,
            title = "Viral Secrets",
            hookSentence = "Today we talk about viral short form videos.",
            viralityReason = "Strong opening hook",
            caption = "Secrets to viral videos",
            hashtags = listOf("#viral", "#podcast")
        )

        val result = verifier.verifyCandidate(
            candidate = candidate,
            groundTruthWords = verifier.extractGroundTruthWords(transcript),
            videoDurationMs = videoDurationMs,
            minLengthSeconds = 60
        )

        assertEquals(VerificationStatus.VERIFIED, result.status)
        assertTrue(result.issues.isEmpty())
        assertTrue(result.isIncludedInExport)
        assertEquals(10000L, result.verifiedRanges.first().startMs)
        assertEquals(75000L, result.verifiedRanges.first().endMs)
    }

    @Test
    fun testVerifyCandidate_inventedQuote_markedInvalid() {
        val transcript = createSampleTranscript()
        val videoDurationMs = 300000L

        // Candidate with completely fabricated quote not in the transcript
        val candidate = ClipCandidate(
            clipId = "c_fake",
            sourceRanges = listOf(SourceRange(10000L, 75000L, rawStart = "00:10", rawEnd = "01:15")),
            hookStartMs = 12000L,
            hookEndMs = 18000L,
            rawHookStart = "00:12",
            rawHookEnd = "00:18",
            transcriptText = "The aliens landed at the Eiffel Tower yesterday and bought fresh croissants for breakfast.",
            score = 95f,
            title = "Aliens in Paris",
            hookSentence = "Aliens bought fresh croissants.",
            viralityReason = "Wild claim",
            caption = "Aliens landed",
            hashtags = listOf("#aliens")
        )

        val result = verifier.verifyCandidate(
            candidate = candidate,
            groundTruthWords = verifier.extractGroundTruthWords(transcript),
            videoDurationMs = videoDurationMs,
            minLengthSeconds = 60
        )

        assertEquals(VerificationStatus.INVALID, result.status)
        assertTrue(result.issues.any { it.contains("quote not found in transcript — AI may have invented it") })
        assertFalse(result.isIncludedInExport)
    }

    @Test
    fun testVerifyCandidate_timingDiscrepancyOver10s_markedNeedsReview() {
        val transcript = createSampleTranscript()
        val videoDurationMs = 300000L

        // Transcript quote is real ("Welcome to the podcast. Today we talk about viral short form videos..."),
        // which occurs at 00:10-00:35,
        // BUT AI hallucinated the timestamp at 02:00-03:10 (discrepancy > 10s)!
        val candidate = ClipCandidate(
            clipId = "c_time_drift",
            sourceRanges = listOf(SourceRange(120000L, 190000L, rawStart = "02:00", rawEnd = "03:10")),
            hookStartMs = 122000L,
            hookEndMs = 128000L,
            rawHookStart = "02:02",
            rawHookEnd = "02:08",
            transcriptText = "Welcome to the podcast. Today we talk about viral short form videos and creative storytelling.",
            score = 80f,
            title = "Misplaced Timestamps",
            hookSentence = "Today we talk about viral short form videos.",
            viralityReason = "Great hook",
            caption = "Viral storytelling",
            hashtags = listOf("#storytelling")
        )

        val result = verifier.verifyCandidate(
            candidate = candidate,
            groundTruthWords = verifier.extractGroundTruthWords(transcript),
            videoDurationMs = videoDurationMs,
            minLengthSeconds = 60
        )

        assertEquals(VerificationStatus.NEEDS_REVIEW, result.status)
        assertTrue(result.issues.any { it.contains("Timing discrepancy > 10s") })
        assertNotNull(result.actualFoundStartMs)
        assertNotNull(result.actualFoundEndMs)

        // Accepting found time should promote to VERIFIED
        val accepted = verifier.acceptFoundTime(result)
        assertEquals(VerificationStatus.VERIFIED, accepted.status)
        assertTrue(accepted.isIncludedInExport)
    }

    @Test
    fun testVerifyCandidate_hookOutsideRange_isClamped() {
        val transcript = createSampleTranscript()
        val videoDurationMs = 300000L

        // Hook is set to 00:05 (before clip starts at 00:10)
        val candidate = ClipCandidate(
            clipId = "c_hook_out",
            sourceRanges = listOf(SourceRange(10000L, 75000L, rawStart = "00:10", rawEnd = "01:15")),
            hookStartMs = 5000L, // 5s before clip starts
            hookEndMs = 9000L,
            rawHookStart = "00:05",
            rawHookEnd = "00:09",
            transcriptText = "Welcome to the podcast. Today we talk about viral short form videos and creative storytelling.",
            score = 78f,
            title = "Hook Outside Test",
            hookSentence = "Welcome to the podcast.",
            viralityReason = "Intro",
            caption = "Podcast intro",
            hashtags = listOf("#intro")
        )

        val result = verifier.verifyCandidate(
            candidate = candidate,
            groundTruthWords = verifier.extractGroundTruthWords(transcript),
            videoDurationMs = videoDurationMs,
            minLengthSeconds = 60
        )

        // Hook should be clamped into range 10000..75000
        assertTrue(result.verifiedHookStartMs >= 10000L)
        assertTrue(result.verifiedHookEndMs <= 75000L)
        assertTrue(result.issues.any { it.contains("fell outside clip range; clamped to") })
    }

    @Test
    fun testVerifyCandidate_durationShorterThanMin_markedInvalid() {
        val transcript = createSampleTranscript()
        val videoDurationMs = 300000L

        // 20s clip when 60s is requested (outside 5s tolerance)
        val candidate = ClipCandidate(
            clipId = "c_short",
            sourceRanges = listOf(SourceRange(10000L, 30000L, rawStart = "00:10", rawEnd = "00:30")),
            hookStartMs = 12000L,
            hookEndMs = 18000L,
            rawHookStart = "00:12",
            rawHookEnd = "00:18",
            transcriptText = "Welcome to the podcast. Today we talk about viral short form videos.",
            score = 65f,
            title = "Too Short",
            hookSentence = "Welcome to the podcast.",
            viralityReason = "Short hook",
            caption = "Short clip",
            hashtags = listOf("#short")
        )

        val result = verifier.verifyCandidate(
            candidate = candidate,
            groundTruthWords = verifier.extractGroundTruthWords(transcript),
            videoDurationMs = videoDurationMs,
            minLengthSeconds = 60
        )

        assertEquals(VerificationStatus.INVALID, result.status)
        assertTrue(result.issues.any { it.contains("shorter than minimum required") })
    }

    @Test
    fun testManualEdit_validTimes_becomesVerified() {
        val transcript = createSampleTranscript()
        val original = verifier.verifyCandidate(
            candidate = ClipCandidate(
                clipId = "c_edit",
                sourceRanges = listOf(SourceRange(10000L, 75000L)),
                transcriptText = "Invalid text that was rejected initially",
                score = 50f
            ),
            groundTruthWords = verifier.extractGroundTruthWords(transcript),
            videoDurationMs = 300000L,
            minLengthSeconds = 60
        )

        val edited = verifier.verifyManualEdit(
            originalClip = original,
            newStartMs = 15000L,
            newEndMs = 85000L,
            newHookStartMs = 16000L,
            newHookEndMs = 22000L,
            videoDurationMs = 300000L,
            minLengthSeconds = 60
        )

        assertEquals(VerificationStatus.VERIFIED, edited.status)
        assertTrue(edited.isManuallyEdited)
        assertTrue(edited.issues.any { it.contains("Manually set by user") })
        assertEquals(15000L, edited.verifiedRanges.first().startMs)
        assertEquals(85000L, edited.verifiedRanges.first().endMs)
    }

    private fun createSampleTranscript(): ProjectTranscript {
        return ProjectTranscript(
            segments = listOf(
                TranscriptSegment(
                    id = "s1",
                    startMs = 10000L,
                    endMs = 25000L,
                    text = "Welcome to the podcast. Today we talk about viral short form videos",
                    words = listOf(
                        TranscriptWord("Welcome", 10000L, 10800L),
                        TranscriptWord("to", 10800L, 11000L),
                        TranscriptWord("the", 11000L, 11200L),
                        TranscriptWord("podcast.", 11200L, 12000L),
                        TranscriptWord("Today", 12100L, 12600L),
                        TranscriptWord("we", 12600L, 12800L),
                        TranscriptWord("talk", 12800L, 13400L),
                        TranscriptWord("about", 13400L, 14000L),
                        TranscriptWord("viral", 14000L, 14800L),
                        TranscriptWord("short", 14800L, 15300L),
                        TranscriptWord("form", 15300L, 15900L),
                        TranscriptWord("videos", 15900L, 16800L)
                    )
                ),
                TranscriptSegment(
                    id = "s2",
                    startMs = 26000L,
                    endMs = 75000L,
                    text = "and creative storytelling that engages viewers immediately from the first second.",
                    words = listOf(
                        TranscriptWord("and", 26000L, 29000L),
                        TranscriptWord("creative", 29000L, 34000L),
                        TranscriptWord("storytelling", 34000L, 42000L),
                        TranscriptWord("that", 42000L, 46000L),
                        TranscriptWord("engages", 46000L, 52000L),
                        TranscriptWord("viewers", 52000L, 57000L),
                        TranscriptWord("immediately", 57000L, 63000L),
                        TranscriptWord("from", 63000L, 66000L),
                        TranscriptWord("the", 66000L, 68000L),
                        TranscriptWord("first", 68000L, 71000L),
                        TranscriptWord("second.", 71000L, 74000L)
                    )
                )
            )
        )
    }
}
