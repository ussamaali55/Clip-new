package com.clipgenius.ai.transcription

import android.content.Context
import android.util.Log
import com.clipgenius.ai.BuildConfig
import com.clipgenius.ai.aiplanner.GeminiConfig
import com.clipgenius.ai.aiplanner.GeminiModelNotFoundException
import com.clipgenius.ai.data.SecurePreferences
import com.clipgenius.ai.state.AudioChunk
import com.clipgenius.ai.state.TranscriptSegment
import com.clipgenius.ai.state.TranscriptWord
import com.clipgenius.ai.util.CrashLogger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Phase 4 Fallback: Direct Gemini Audio Transcription using Gemini File API and GeminiConfig.
 * Used when Deepgram key is absent or quota limit is reached.
 */
class GeminiFallbackTranscriber(private val context: Context) {

    companion object {
        private const val TAG = "GeminiFallback"
    }

    private val securePreferences = SecurePreferences(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    fun getApiKey(): String {
        val userSavedKey = securePreferences.getGeminiApiKey().trim()
        if (userSavedKey.isNotEmpty()) return userSavedKey

        return try {
            val buildConfigKey = BuildConfig::class.java.getField("GEMINI_API_KEY").get(null) as? String
            buildConfigKey?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun hasApiKey(): Boolean {
        return getApiKey().isNotBlank()
    }

    suspend fun transcribeChunk(chunk: AudioChunk): List<TranscriptSegment> {
        val apiKey = getApiKey()
        if (apiKey.isBlank()) {
            throw IOException("Gemini API key not found. Please add it in Settings → API Keys.")
        }

        val chunkFile = File(chunk.filePath)
        if (!chunkFile.exists() || chunkFile.length() <= 0L) {
            throw IOException("Chunk audio file missing at: ${chunk.filePath}")
        }

        // 1. Upload audio chunk to Gemini File API
        val fileUri = uploadChunkToGeminiFiles(chunkFile, apiKey)
        Log.i(TAG, "Uploaded chunk ${chunk.index} to Gemini Files API: $fileUri")

        // 2. Call Gemini generateContent with structured JSON and automatic model fallback
        return generateTranscriptionWithGemini(fileUri, apiKey, chunk.startMs)
    }

    private fun uploadChunkToGeminiFiles(file: File, apiKey: String): String {
        // Step 1: Initiate resumable upload
        val initUrl = "https://generativelanguage.googleapis.com/upload/v1beta/files?key=$apiKey"
        val initJson = JSONObject().apply {
            val fileObj = JSONObject().apply {
                put("display_name", file.name)
            }
            put("file", fileObj)
        }

        val initRequest = Request.Builder()
            .url(initUrl)
            .header("X-Goog-Upload-Protocol", "resumable")
            .header("X-Goog-Upload-Command", "start")
            .header("X-Goog-Upload-Header-Content-Length", file.length().toString())
            .header("X-Goog-Upload-Header-Content-Type", "audio/mp4")
            .header("Content-Type", "application/json")
            .post(initJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val uploadUrl = httpClient.newCall(initRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                throw IOException("Gemini File upload initialization failed (${response.code}): $err")
            }
            response.header("x-goog-upload-url")
                ?: throw IOException("Missing x-goog-upload-url header in Gemini File upload response.")
        }

        // Step 2: Upload bytes
        val fileRequestBody = file.asRequestBody("audio/mp4".toMediaType())
        val uploadRequest = Request.Builder()
            .url(uploadUrl)
            .header("Content-Length", file.length().toString())
            .header("X-Goog-Upload-Offset", "0")
            .header("X-Goog-Upload-Command", "upload, finalize")
            .post(fileRequestBody)
            .build()

        val uploadedJson = httpClient.newCall(uploadRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                throw IOException("Gemini File upload failed (${response.code}): $err")
            }
            response.body?.string() ?: ""
        }

        val fileObj = JSONObject(uploadedJson).optJSONObject("file")
            ?: throw IOException("Missing file object in Gemini upload response.")
        return fileObj.optString("uri")
    }

    private fun generateTranscriptionWithGemini(
        fileUri: String,
        apiKey: String,
        chunkStartMs: Long
    ): List<TranscriptSegment> {
        val promptText = "Transcribe this audio with word-level timestamps as JSON. " +
                "Output a JSON object with this exact structure: " +
                "{\"segments\": [{\"start\": 0.0, \"end\": 2.5, \"speaker\": 1, \"text\": \"hello world\", \"words\": [{\"word\": \"hello\", \"start\": 0.0, \"end\": 0.8, \"speaker\": 1}, {\"word\": \"world\", \"start\": 0.9, \"end\": 2.5, \"speaker\": 1}]}]}"

        val requestJson = JSONObject().apply {
            val contents = org.json.JSONArray().apply {
                val contentObj = JSONObject().apply {
                    val parts = org.json.JSONArray().apply {
                        val filePart = JSONObject().apply {
                            val fileData = JSONObject().apply {
                                put("mimeType", "audio/mp4")
                                put("fileUri", fileUri)
                            }
                            put("fileData", fileData)
                        }
                        val textPart = JSONObject().apply {
                            put("text", promptText)
                        }
                        put(filePart)
                        put(textPart)
                    }
                    put("parts", parts)
                }
                put(contentObj)
            }
            put("contents", contents)

            val genConfig = JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.2)
            }
            put("generationConfig", genConfig)
        }

        val requestBody = requestJson.toString().toRequestBody("application/json".toMediaType())
        val candidateModels = GeminiConfig.getCandidateModels(context)
        var lastException: Exception? = null

        for ((index, model) in candidateModels.withIndex()) {
            try {
                CrashLogger.addBreadcrumb("GeminiFallbackTranscriber: attempting transcription with model '$model' (${index + 1}/${candidateModels.size})")

                val generateUrl = GeminiConfig.getEndpointUrl(model, apiKey)
                val request = Request.Builder()
                    .url(generateUrl)
                    .header("Content-Type", "application/json")
                    .post(requestBody)
                    .build()

                val responseString = httpClient.newCall(request).execute().use { response ->
                    val bodyString = response.body?.string() ?: ""
                    if (!response.isSuccessful) {
                        val lowerBody = bodyString.lowercase()
                        val isModelUnavailable = response.code == 404 ||
                                (response.code == 400 && (lowerBody.contains("not available") || lowerBody.contains("not found") || lowerBody.contains("model")))
                        if (isModelUnavailable) {
                            throw GeminiModelNotFoundException(model, response.code, bodyString)
                        }
                        throw IOException("Gemini transcription failed (${response.code}): $bodyString")
                    }
                    bodyString
                }

                val segments = parseGeminiResponse(responseString, chunkStartMs)
                CrashLogger.addBreadcrumb("GeminiFallbackTranscriber: transcription succeeded with model '$model'")
                return segments

            } catch (e: GeminiModelNotFoundException) {
                lastException = e
                val nextModel = candidateModels.getOrNull(index + 1)
                if (nextModel != null) {
                    CrashLogger.addBreadcrumb("GeminiFallbackTranscriber: model '$model' unavailable (404). Falling back to '$nextModel'.")
                    CrashLogger.logError(context, TAG, "Model '$model' 404 not available. Retrying with '$nextModel'", e)
                } else {
                    CrashLogger.addBreadcrumb("GeminiFallbackTranscriber: all candidate models exhausted after 404 on '$model'.")
                    CrashLogger.logError(context, TAG, "All models exhausted for fallback transcription. Final error on '$model'", e)
                }
            } catch (e: Exception) {
                lastException = e
                val msg = e.message ?: ""
                val nextModel = candidateModels.getOrNull(index + 1)
                if (nextModel != null) {
                    CrashLogger.addBreadcrumb("GeminiFallbackTranscriber: error on model '$model' ($msg). Retrying with fallback '$nextModel'.")
                    CrashLogger.logError(context, TAG, "Error on model '$model' ($msg), retrying with '$nextModel'", e)
                } else {
                    break
                }
            }
        }

        throw lastException ?: IOException("Gemini transcription failed for all configured models.")
    }

    fun parseGeminiResponse(jsonString: String, chunkStartMs: Long): List<TranscriptSegment> {
        val root = JSONObject(jsonString)
        val candidates = root.optJSONArray("candidates") ?: throw IOException("Gemini response missing candidates.")
        if (candidates.length() == 0) throw IOException("Gemini returned empty candidates.")

        val firstCandidate = candidates.getJSONObject(0)
        val content = firstCandidate.optJSONObject("content") ?: throw IOException("Missing content in Gemini candidate.")
        val parts = content.optJSONArray("parts") ?: throw IOException("Missing parts in Gemini content.")
        if (parts.length() == 0) throw IOException("Empty parts in Gemini content.")

        val textContent = parts.getJSONObject(0).optString("text", "")
        val transcriptJson = JSONObject(textContent)

        val segments = mutableListOf<TranscriptSegment>()
        val segArray = transcriptJson.optJSONArray("segments") ?: org.json.JSONArray()

        for (i in 0 until segArray.length()) {
            val segObj = segArray.getJSONObject(i)
            val sSec = segObj.optDouble("start", 0.0)
            val eSec = segObj.optDouble("end", 0.0)
            val text = segObj.optString("text", "").trim()
            val speaker = if (segObj.has("speaker")) segObj.optInt("speaker") else 1
            val speakerLabel = "Speaker $speaker"

            val startMs = (sSec * 1000).toLong() + chunkStartMs
            val endMs = (eSec * 1000).toLong() + chunkStartMs

            val wordsList = mutableListOf<TranscriptWord>()
            val wordsArray = segObj.optJSONArray("words")
            if (wordsArray != null) {
                for (w in 0 until wordsArray.length()) {
                    val wObj = wordsArray.getJSONObject(w)
                    val wText = wObj.optString("word", "").trim()
                    val wStart = (wObj.optDouble("start", sSec) * 1000).toLong() + chunkStartMs
                    val wEnd = (wObj.optDouble("end", eSec) * 1000).toLong() + chunkStartMs
                    val wSpeaker = if (wObj.has("speaker")) wObj.optInt("speaker") else speaker

                    if (wText.isNotBlank()) {
                        wordsList.add(
                            TranscriptWord(
                                word = wText,
                                startMs = wStart,
                                endMs = wEnd,
                                speaker = wSpeaker,
                                confidence = 0.85f
                            )
                        )
                    }
                }
            }

            if (text.isNotBlank()) {
                segments.add(
                    TranscriptSegment(
                        id = UUID.randomUUID().toString(),
                        startMs = startMs,
                        endMs = maxOf(endMs, startMs + 100L),
                        text = text,
                        speakerLabel = speakerLabel,
                        words = wordsList
                    )
                )
            }
        }

        return segments
    }
}
