package com.clipgenius.ai.transcription

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeepgramParserTest {

    @Test
    fun testParseDeepgramUtterancesWithOffset() {
        val json = """
        {
          "results": {
            "channels": [],
            "utterances": [
              {
                "start": 1.25,
                "end": 3.75,
                "confidence": 0.98,
                "channel": 0,
                "transcript": "Hello and welcome.",
                "speaker": 0,
                "words": [
                  {
                    "word": "hello",
                    "start": 1.25,
                    "end": 1.75,
                    "confidence": 0.99,
                    "speaker": 0,
                    "punctuated_word": "Hello"
                  },
                  {
                    "word": "and",
                    "start": 1.80,
                    "end": 2.10,
                    "confidence": 0.97,
                    "speaker": 0,
                    "punctuated_word": "and"
                  },
                  {
                    "word": "welcome",
                    "start": 2.20,
                    "end": 3.75,
                    "confidence": 0.98,
                    "speaker": 0,
                    "punctuated_word": "welcome."
                  }
                ]
              }
            ]
          }
        }
        """.trimIndent()

        // Suppose chunk starts at 60,000 ms (1 minute into the original video)
        val chunkStartMs = 60000L
        val segments = DeepgramClient.parseDeepgramResponse(json, chunkStartMs)

        assertEquals(1, segments.size)
        val seg = segments[0]
        assertEquals("Speaker 1", seg.speakerLabel)
        assertEquals("Hello and welcome.", seg.text)
        assertEquals(61250L, seg.startMs)
        assertEquals(63750L, seg.endMs)

        assertEquals(3, seg.words.size)
        assertEquals("Hello", seg.words[0].word)
        assertEquals(61250L, seg.words[0].startMs)
        assertEquals(61750L, seg.words[0].endMs)

        assertEquals("welcome.", seg.words[2].word)
        assertEquals(62200L, seg.words[2].startMs)
        assertEquals(63750L, seg.words[2].endMs)
    }
}
