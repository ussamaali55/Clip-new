package com.clipgenius.ai.aiplanner

import androidx.test.core.app.ApplicationProvider
import com.clipgenius.ai.state.ClipCandidate
import com.clipgenius.ai.state.SourceRange
import com.clipgenius.ai.state.TranscriptSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiClipPlannerTest {

    private lateinit var planner: AiClipPlanner

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        planner = AiClipPlanner(context)
    }

    @Test
    fun testFormatTranscriptCompact() {
        val segments = listOf(
            TranscriptSegment(
                id = "seg-1",
                startMs = 0L,
                endMs = 4500L,
                text = "Welcome to our podcast episode.",
                speakerLabel = "Host"
            ),
            TranscriptSegment(
                id = "seg-2",
                startMs = 5000L,
                endMs = 12000L,
                text = "Today we discuss viral video creation.",
                speakerLabel = "Guest"
            )
        )

        val formatted = planner.formatTranscript(segments)
        assertTrue(formatted.contains("[00:00] Host: Welcome to our podcast episode."))
        assertTrue(formatted.contains("[00:05] Guest: Today we discuss viral video creation."))
    }

    @Test
    fun testParseTimestampToMs() {
        assertEquals(0L, planner.parseTimestampToMs("00:00"))
        assertEquals(75000L, planner.parseTimestampToMs("01:15"))
        assertEquals(3661000L, planner.parseTimestampToMs("01:01:01"))
        assertEquals(null, planner.parseTimestampToMs("invalid"))
    }

    @Test
    fun testParseAndValidateClips() {
        val validJson = """
            {
              "clips": [
                {
                  "clipId": "clip-1",
                  "sourceRanges": [{"start": "00:10", "end": "01:15"}],
                  "hookStart": "00:10",
                  "hookEnd": "00:13",
                  "transcriptText": "This is why video goes viral instantly.",
                  "score": 88,
                  "title": "The Secret to Going Viral",
                  "hookSentence": "This is why video goes viral instantly.",
                  "viralityReason": "High emotional opening with counter-intuitive secret.",
                  "caption": "The formula behind millions of views revealed! 🚀",
                  "hashtags": ["viral", "contentcreator", "shorts"]
                },
                {
                  "clipId": "clip-invalid",
                  "sourceRanges": [],
                  "score": 150,
                  "title": ""
                }
              ]
            }
        """.trimIndent()

        val clips = planner.parseAndValidateClips(
            jsonString = validJson,
            videoDurationMs = 180000L, // 3 minutes
            minLengthSeconds = 60
        )

        assertEquals(1, clips.size)
        val clip = clips.first()
        assertEquals("clip-1", clip.clipId)
        assertEquals(88f, clip.score)
        assertEquals("The Secret to Going Viral", clip.title)
        assertEquals(10000L, clip.sourceRanges.first().startMs)
        assertEquals(75000L, clip.sourceRanges.first().endMs)
        assertEquals("This is why video goes viral instantly.", clip.hookSentence)
        assertEquals(3, clip.hashtags.size)
        assertTrue(clip.isIncludedInExport)
    }

    @Test
    fun testDeduplicateAndRankClips() {
        val clipA = ClipCandidate(
            clipId = "a",
            sourceRanges = listOf(SourceRange(startMs = 10000L, endMs = 70000L)),
            score = 75f,
            title = "Lower Score Overlapping"
        )
        val clipB = ClipCandidate(
            clipId = "b",
            sourceRanges = listOf(SourceRange(startMs = 12000L, endMs = 72000L)),
            score = 92f,
            title = "Higher Score Overlapping"
        )
        val clipC = ClipCandidate(
            clipId = "c",
            sourceRanges = listOf(SourceRange(startMs = 120000L, endMs = 180000L)),
            score = 80f,
            title = "Non Overlapping"
        )

        val ranked = planner.deduplicateAndRankClips(listOf(clipA, clipB, clipC), maxCount = 10)

        // Clip B and C should be retained; Clip A discarded due to overlap with higher-scoring Clip B
        assertEquals(2, ranked.size)
        assertEquals("b", ranked[0].clipId)
        assertEquals(92f, ranked[0].score)
        assertEquals("c", ranked[1].clipId)
        assertEquals(80f, ranked[1].score)
    }
}
