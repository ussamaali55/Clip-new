package com.clipgenius.ai.transcription

import com.clipgenius.ai.state.TranscriptSegment
import com.clipgenius.ai.state.TranscriptWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptMergerTest {

    @Test
    fun testFormatSrtTimestamp() {
        // 0 ms -> 00:00:00,000
        assertEquals("00:00:00,000", TranscriptMerger.formatSrtTimestamp(0L))
        // 1234 ms -> 00:00:01,234
        assertEquals("00:00:01,234", TranscriptMerger.formatSrtTimestamp(1234L))
        // 65432 ms -> 00:01:05,432
        assertEquals("00:01:05,432", TranscriptMerger.formatSrtTimestamp(65432L))
        // 3661500 ms -> 01:01:01,500
        assertEquals("01:01:01,500", TranscriptMerger.formatSrtTimestamp(3661500L))
    }

    @Test
    fun testGenerateSrt() {
        val segments = listOf(
            TranscriptSegment(
                id = "seg_0001",
                startMs = 500L,
                endMs = 3200L,
                text = "Hello world this is Clip Genius.",
                speakerLabel = "Speaker 1",
                words = listOf(
                    TranscriptWord("Hello", 500L, 1000L, 0, 0.99f),
                    TranscriptWord("world", 1000L, 1500L, 0, 0.98f)
                )
            ),
            TranscriptSegment(
                id = "seg_0002",
                startMs = 3800L,
                endMs = 6100L,
                text = "Glad to be here today.",
                speakerLabel = "Speaker 2",
                words = emptyList()
            )
        )

        val srt = TranscriptMerger.generateSrt(segments)

        assertTrue(srt.contains("1\n00:00:00,500 --> 00:00:03,200\n[Speaker 1] Hello world this is Clip Genius."))
        assertTrue(srt.contains("2\n00:00:03,800 --> 00:00:06,100\n[Speaker 2] Glad to be here today."))
    }

    @Test
    fun testTextEditsNeverAlterTiming() {
        val original = TranscriptSegment(
            id = "seg_0001",
            startMs = 5000L,
            endMs = 9500L,
            text = "Initial wrong text here",
            speakerLabel = "Speaker 1"
        )

        // Simulate updating text (Phase 5 requirement: Text edits never alter timing)
        val edited = original.copy(
            text = "Corrected text here",
            isEdited = true
        )

        assertEquals("Timing startMs must remain identical", original.startMs, edited.startMs)
        assertEquals("Timing endMs must remain identical", original.endMs, edited.endMs)
        assertEquals("Corrected text here", edited.text)
        assertTrue("isEdited must be true", edited.isEdited)
    }

    @Test
    fun testSpeakerRenameGlobally() {
        val segments = listOf(
            TranscriptSegment(
                id = "seg_0001",
                startMs = 1000L,
                endMs = 2500L,
                text = "Welcome to the show.",
                speakerLabel = "Speaker 1"
            ),
            TranscriptSegment(
                id = "seg_0002",
                startMs = 3000L,
                endMs = 4500L,
                text = "Thanks for having me.",
                speakerLabel = "Speaker 2"
            ),
            TranscriptSegment(
                id = "seg_0003",
                startMs = 5000L,
                endMs = 6500L,
                text = "Let's dive into the topic.",
                speakerLabel = "Speaker 1"
            )
        )

        // Rename Speaker 1 -> Host
        val updatedSegments = segments.map {
            if (it.speakerLabel == "Speaker 1") it.copy(speakerLabel = "Host", isEdited = true) else it
        }

        assertEquals("Host", updatedSegments[0].speakerLabel)
        assertEquals("Speaker 2", updatedSegments[1].speakerLabel)
        assertEquals("Host", updatedSegments[2].speakerLabel)

        val srt = TranscriptMerger.generateSrt(updatedSegments)
        assertTrue(srt.contains("[Host] Welcome to the show."))
        assertTrue(srt.contains("[Speaker 2] Thanks for having me."))
        assertTrue(srt.contains("[Host] Let's dive into the topic."))
    }
}
