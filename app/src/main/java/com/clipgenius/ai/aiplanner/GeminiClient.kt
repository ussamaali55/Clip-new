package com.clipgenius.ai.aiplanner

import android.content.Context
import com.clipgenius.ai.data.SecurePreferences
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Phase 6 Gemini REST Client using OkHttp.
 * - Reads API key ONLY from EncryptedSharedPreferences (via SecurePreferences).
 * - NEVER logs the API key under any circumstance.
 * - Uses configurable GEMINI_MODEL constant.
 * - Handles 429/5xx with exponential backoff (2s, 4s, 8s, max 4 tries).
 * - Translates HTTP 400 invalid-key and quota errors to clear user messages.
 */
class GeminiClient(private val context: Context) {

    companion object {
        /**
         * Configurable Gemini model name constant so it can be changed in one place.
         */
        const val GEMINI_MODEL = "gemini-2.5-flash"

        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    private val securePreferences = SecurePreferences(context)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Checks if a Gemini key is present in EncryptedSharedPreferences.
     */
    fun hasApiKey(): Boolean {
        return securePreferences.getGeminiApiKey().trim().isNotEmpty()
    }

    /**
     * Calls Gemini generateContent with the system prompt and transcript content.
     * Retries once if response JSON parsing fails.
     */
    suspend fun generateContentWithRetry(
        systemInstruction: String,
        promptText: String
    ): String {
        var parseAttempt = 0
        while (parseAttempt < 2) {
            parseAttempt++
            val rawResponse = callGenerateContent(systemInstruction, promptText)
            val extractedJson = extractJsonText(rawResponse)
            if (isValidJsonStructure(extractedJson)) {
                return extractedJson
            }
            if (parseAttempt < 2) {
                // Wait briefly before retrying the call once
                delay(1000L)
            }
        }
        throw IOException("Gemini returned an unusable answer. Tap Retry.")
    }

    /**
     * Core REST call to Gemini with exponential backoff for 429/5xx (2s, 4s, 8s, max 4 tries).
     * NEVER logs the API key.
     */
    private suspend fun callGenerateContent(
        systemInstruction: String,
        promptText: String
    ): String {
        // Read key ONLY from EncryptedSharedPreferences
        val apiKey = securePreferences.getGeminiApiKey().trim()
        if (apiKey.isEmpty()) {
            throw IOException("Gemini API key not found. Add it in Settings → API Keys.")
        }

        val requestPayload = JSONObject().apply {
            // System instruction
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply {
                        put("text", systemInstruction)
                    })
                })
            })

            // User prompt / transcript
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", promptText)
                        })
                    })
                })
            })

            // Strict JSON generation
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.2)
            })
        }

        val requestBody = requestPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        // Build URL without logging it
        val endpointUrl = "$BASE_URL/$GEMINI_MODEL:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(endpointUrl)
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build()

        var currentDelayMs = 2000L
        val maxTries = 4

        for (attempt in 1..maxTries) {
            try {
                val response = httpClient.newCall(request).execute()
                val responseCode = response.code
                val responseBodyString = response.body?.string() ?: ""

                if (response.isSuccessful) {
                    return responseBodyString
                }

                val lowerBody = responseBodyString.lowercase()

                // Check for 400 Invalid API Key
                if (responseCode == 400) {
                    if (lowerBody.contains("api_key_invalid") ||
                        lowerBody.contains("key not valid") ||
                        lowerBody.contains("invalid api key") ||
                        lowerBody.contains("api key not found")
                    ) {
                        throw IOException("Gemini rejected the API key. Check the key in Settings.")
                    } else {
                        // General 400 error
                        throw IOException("Gemini request error: ${extractErrorMessage(responseBodyString)}")
                    }
                }

                // Check for Quota / Rate limit (429 or specific quota message)
                val isQuota = responseCode == 429 ||
                        lowerBody.contains("resource_exhausted") ||
                        lowerBody.contains("quota") ||
                        lowerBody.contains("rate limit")

                if (isQuota) {
                    if (attempt < maxTries && responseCode != 429) {
                        delay(currentDelayMs)
                        currentDelayMs *= 2
                        continue
                    }
                    throw IOException("Your Gemini key has hit its free limit. Wait a while or replace the key in Settings.")
                }

                // 429 rate limit or 5xx server errors retry with exponential backoff (2s, 4s, 8s, max 4 tries)
                if (responseCode == 429 || (responseCode in 500..599)) {
                    if (attempt < maxTries) {
                        delay(currentDelayMs)
                        currentDelayMs *= 2
                        continue
                    }
                    if (responseCode == 429) {
                        throw IOException("Your Gemini key has hit its free limit. Wait a while or replace the key in Settings.")
                    }
                    throw IOException("Gemini server error ($responseCode). Tap Retry.")
                }

                // Any other non-successful response
                throw IOException("Gemini API call failed ($responseCode): ${extractErrorMessage(responseBodyString)}")

            } catch (e: IOException) {
                // If it's already one of our user-friendly translated errors, rethrow immediately
                val msg = e.message ?: ""
                if (msg.contains("Gemini rejected the API key") ||
                    msg.contains("Your Gemini key has hit its free limit") ||
                    msg.contains("Gemini API key not found")
                ) {
                    throw e
                }

                if (attempt < maxTries) {
                    delay(currentDelayMs)
                    currentDelayMs *= 2
                } else {
                    throw e
                }
            }
        }

        throw IOException("Failed to reach Gemini after $maxTries attempts. Please check network connection.")
    }

    /**
     * Extracts text content from Gemini's generateContent response JSON.
     */
    private fun extractJsonText(responseJsonString: String): String {
        return try {
            val root = JSONObject(responseJsonString)
            val candidates = root.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            if (parts.length() == 0) return ""
            val part = parts.getJSONObject(0)
            val rawText = part.optString("text", "").trim()

            // Remove markdown code fences if Gemini added them despite responseMimeType
            cleanCodeFences(rawText)
        } catch (e: Exception) {
            cleanCodeFences(responseJsonString.trim())
        }
    }

    private fun cleanCodeFences(text: String): String {
        var clean = text
        if (clean.startsWith("```json")) {
            clean = clean.removePrefix("```json").trim()
        } else if (clean.startsWith("```")) {
            clean = clean.removePrefix("```").trim()
        }
        if (clean.endsWith("```")) {
            clean = clean.removeSuffix("```").trim()
        }
        return clean
    }

    private fun isValidJsonStructure(text: String): Boolean {
        if (text.isBlank()) return false
        return try {
            val obj = JSONObject(text)
            obj.has("clips")
        } catch (e: Exception) {
            false
        }
    }

    private fun extractErrorMessage(responseBody: String): String {
        return try {
            val obj = JSONObject(responseBody)
            val errorObj = obj.optJSONObject("error")
            errorObj?.optString("message", "Unknown error") ?: "Unknown error"
        } catch (e: Exception) {
            "Unknown error"
        }
    }
}
