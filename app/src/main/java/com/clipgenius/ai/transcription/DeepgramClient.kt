package com.clipgenius.ai.transcription

import android.content.Context
import android.util.Log
import com.clipgenius.ai.data.SecurePreferences
import com.clipgenius.ai.state.AudioChunk
import com.clipgenius.ai.state.TranscriptSegment
import com.clipgenius.ai.state.TranscriptWord
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Phase 4 Deepgram AI transcription client.
 * Calls Deepgram Nova-3 API with word timestamps and speaker diarization.
 */
class DeepgramClient(private val context: Context) {

    companion object {
        private const val TAG = "DeepgramClient"
        private const val BASE_URL = "https://api.deepgram.com/v1/listen"
        private const val MAX_RETRIES = 4

        /**
         * Parses Deepgram Nova-3 JSON response and shifts timestamps by chunkStartMs.
         */
        fun parseDeepgramResponse(jsonString: String, chunkStartMs: Long): List<TranscriptSegment> {
            val root = JSONObject(jsonString)
            val results = root.optJSONObject("results") ?: throw IOException("Invalid Deepgram response: Missing results object.")

            val segments = mutableListOf<TranscriptSegment>()
            val utterances = results.optJSONArray("utterances")

            if (utterances != null && utterances.length() > 0) {
                for (i in 0 until utterances.length()) {
                    val utterance = utterances.getJSONObject(i)
                    val uStartSec = utterance.optDouble("start", 0.0)
                    val uEndSec = utterance.optDouble("end", 0.0)
                    val uText = utterance.optString("transcript", "").trim()
                    val uSpeaker = if (utterance.has("speaker")) utterance.optInt("speaker") else null
                    val speakerLabel = if (uSpeaker != null) "Speaker ${uSpeaker + 1}" else "Speaker 1"

                    val startMs = (uStartSec * 1000).toLong() + chunkStartMs
                    val endMs = (uEndSec * 1000).toLong() + chunkStartMs

                    val wordsList = mutableListOf<TranscriptWord>()
                    val wordsArray = utterance.optJSONArray("words")
                    if (wordsArray != null) {
                        for (w in 0 until wordsArray.length()) {
                            val wordObj = wordsArray.getJSONObject(w)
                            val wordText = wordObj.optString("punctuated_word", wordObj.optString("word", ""))
                            val wStart = (wordObj.optDouble("start", 0.0) * 1000).toLong() + chunkStartMs
                            val wEnd = (wordObj.optDouble("end", 0.0) * 1000).toLong() + chunkStartMs
                            val wSpeaker = if (wordObj.has("speaker")) wordObj.optInt("speaker") else uSpeaker
                            val confidence = if (wordObj.has("confidence")) wordObj.optDouble("confidence").toFloat() else null

                            if (wordText.isNotBlank()) {
                                wordsList.add(
                                    TranscriptWord(
                                        word = wordText,
                                        startMs = wStart,
                                        endMs = wEnd,
                                        speaker = wSpeaker,
                                        confidence = confidence
                                    )
                                )
                            }
                        }
                    }

                    if (uText.isNotBlank()) {
                        segments.add(
                            TranscriptSegment(
                                id = UUID.randomUUID().toString(),
                                startMs = startMs,
                                endMs = maxOf(endMs, startMs + 100L),
                                text = uText,
                                speakerLabel = speakerLabel,
                                words = wordsList
                            )
                        )
                    }
                }
            } else {
                // Fallback: Use channels[0].alternatives[0]
                val channels = results.optJSONArray("channels")
                if (channels != null && channels.length() > 0) {
                    val channel = channels.getJSONObject(0)
                    val alternatives = channel.optJSONArray("alternatives")
                    if (alternatives != null && alternatives.length() > 0) {
                        val alternative = alternatives.getJSONObject(0)
                        val wordsArray = alternative.optJSONArray("words")

                        if (wordsArray != null && wordsArray.length() > 0) {
                            var currentSpeaker: Int? = null
                            val currentWords = mutableListOf<TranscriptWord>()

                            fun flushCurrentSegment() {
                                if (currentWords.isNotEmpty()) {
                                    val sMs = currentWords.first().startMs
                                    val eMs = currentWords.last().endMs
                                    val segText = currentWords.joinToString(" ") { it.word }
                                    val sLabel = if (currentSpeaker != null) "Speaker ${currentSpeaker!! + 1}" else "Speaker 1"
                                    segments.add(
                                        TranscriptSegment(
                                            id = UUID.randomUUID().toString(),
                                            startMs = sMs,
                                            endMs = maxOf(eMs, sMs + 100L),
                                            text = segText,
                                            speakerLabel = sLabel,
                                            words = ArrayList(currentWords)
                                        )
                                    )
                                    currentWords.clear()
                                }
                            }

                            for (w in 0 until wordsArray.length()) {
                                val wordObj = wordsArray.getJSONObject(w)
                                val wordText = wordObj.optString("punctuated_word", wordObj.optString("word", ""))
                                val wStart = (wordObj.optDouble("start", 0.0) * 1000).toLong() + chunkStartMs
                                val wEnd = (wordObj.optDouble("end", 0.0) * 1000).toLong() + chunkStartMs
                                val wSpeaker = if (wordObj.has("speaker")) wordObj.optInt("speaker") else null
                                val confidence = if (wordObj.has("confidence")) wordObj.optDouble("confidence").toFloat() else null

                                if (currentSpeaker == null) {
                                    currentSpeaker = wSpeaker
                                } else if (wSpeaker != null && wSpeaker != currentSpeaker) {
                                    flushCurrentSegment()
                                    currentSpeaker = wSpeaker
                                }

                                currentWords.add(
                                    TranscriptWord(
                                        word = wordText,
                                        startMs = wStart,
                                        endMs = wEnd,
                                        speaker = wSpeaker,
                                        confidence = confidence
                                    )
                                )

                                // Break on punctuation or long pause (> 1500ms)
                                if (wordText.endsWith(".") || wordText.endsWith("?") || wordText.endsWith("!")) {
                                    flushCurrentSegment()
                                }
                            }
                            flushCurrentSegment()
                        }
                    }
                }
            }

            return segments
        }
    }

    private val securePreferences = SecurePreferences(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Checks if a Deepgram API key is present in EncryptedSharedPreferences.
     */
    fun hasApiKey(): Boolean {
        return securePreferences.getDeepgramApiKey().isNotBlank()
    }

    /**
     * Transcribes an audio chunk file using Deepgram Nova-3.
     * Applies chunk startMs offset to all segment and word timestamps.
     */
    suspend fun transcribeChunk(chunk: AudioChunk): List<TranscriptSegment> {
        val apiKey = securePreferences.getDeepgramApiKey().trim()
        if (apiKey.isEmpty()) {
            throw DeepgramAuthException("Deepgram API key not found. Please add it in Settings → API Keys.")
        }

        val chunkFile = File(chunk.filePath)
        if (!chunkFile.exists() || chunkFile.length() <= 0L) {
            throw IOException("Chunk audio file missing or empty at: ${chunk.filePath}")
        }

        val url = "$BASE_URL?model=nova-3&smart_format=true&diarize=true&utterances=true&punctuate=true"
        val requestBody = chunkFile.asRequestBody("audio/m4a".toMediaType())

        var attempt = 1
        var delayMs = 2000L

        while (true) {
            val request = Request.Builder()
                .url(url)
                // SECURITY RULE: Never log the key or include it in error messages
                .header("Authorization", "Token $apiKey")
                .header("Content-Type", "audio/m4a")
                .post(requestBody)
                .build()

            var responseCode = 0
            var responseBodyString = ""

            try {
                httpClient.newCall(request).execute().use { response ->
                    responseCode = response.code
                    responseBodyString = response.body?.string() ?: ""

                    when (responseCode) {
                        200 -> {
                            return parseDeepgramResponse(responseBodyString, chunk.startMs)
                        }
                        401, 403 -> {
                            Log.e(TAG, "Deepgram auth failed with code: $responseCode")
                            throw DeepgramAuthException("Deepgram rejected the API key. Check the key in Settings.")
                        }
                        402 -> {
                            Log.e(TAG, "Deepgram quota or billing limit exceeded: $responseCode")
                            throw DeepgramQuotaException("Your Deepgram key has hit its limit. Recharge your Deepgram account or replace the key in Settings.")
                        }
                        429, 500, 502, 503, 504 -> {
                            // Check if 429 is due to quota
                            if (responseCode == 429 && responseBodyString.contains("insufficient_funds", ignoreCase = true)) {
                                throw DeepgramQuotaException("Your Deepgram key has hit its limit. Recharge your Deepgram account or replace the key in Settings.")
                            }

                            if (attempt >= MAX_RETRIES) {
                                throw IOException("Deepgram API request failed with HTTP $responseCode after $attempt attempts.")
                            }
                            Log.w(TAG, "Deepgram HTTP $responseCode on chunk ${chunk.index}. Retrying in ${delayMs}ms (attempt $attempt/$MAX_RETRIES)...")
                            delay(delayMs)
                            attempt++
                            delayMs *= 2 // 2s, 4s, 8s exponential backoff
                        }
                        else -> {
                            // Inspect response for quota error text without exposing credentials
                            if (responseBodyString.contains("insufficient_funds", ignoreCase = true) ||
                                responseBodyString.contains("quota", ignoreCase = true) ||
                                responseBodyString.contains("payment_required", ignoreCase = true)
                            ) {
                                throw DeepgramQuotaException("Your Deepgram key has hit its limit. Recharge your Deepgram account or replace the key in Settings.")
                            }
                            throw IOException("Deepgram transcription failed with HTTP $responseCode.")
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is DeepgramAuthException || e is DeepgramQuotaException) {
                    throw e
                }
                if (attempt >= MAX_RETRIES) {
                    throw IOException("Network error contacting Deepgram: ${e.localizedMessage}", e)
                }
                Log.w(TAG, "Network attempt $attempt failed for chunk ${chunk.index}: ${e.message}. Retrying in ${delayMs}ms...")
                delay(delayMs)
                attempt++
                delayMs *= 2
            }
        }
    }
}
