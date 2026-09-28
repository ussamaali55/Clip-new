package com.clipgenius.ai.aiplanner

import android.content.Context
import com.clipgenius.ai.data.SecurePreferences
import java.io.IOException

/**
 * Single source of truth for Gemini model names, fallbacks, and configuration.
 *
 * Rules:
 * 1. Default model is "gemini-3.8-flash".
 * 2. Ordered fallback list is ["gemini-3.8-flash", "gemini-3.5-flash"].
 * 3. User can override the model in Settings -> Advanced ("Gemini model name").
 * 4. Automatic retry on HTTP 404 (model not available) falls back to the next model in the list.
 */
object GeminiConfig {
    /**
     * Primary Gemini flash model recommended by Google.
     */
    const val MODEL_NAME = "gemini-3.8-flash"
    const val DEFAULT_MODEL = MODEL_NAME

    /**
     * Known-good previous flash model for automatic fallback if primary model is unavailable.
     */
    const val FALLBACK_MODEL = "gemini-3.5-flash"

    /**
     * Ordered list of fallback models.
     */
    val FALLBACK_MODELS: List<String> = listOf(DEFAULT_MODEL, FALLBACK_MODEL)

    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    fun getEndpointUrl(model: String, apiKey: String): String {
        return "$BASE_URL/$model:generateContent?key=$apiKey"
    }

    /**
     * Returns an ordered list of candidate models to try:
     * 1. User override if configured in Settings (and non-blank).
     * 2. Default model ("gemini-3.8-flash").
     * 3. Fallback model ("gemini-3.5-flash").
     * Duplicates are filtered out while preserving order.
     */
    fun getCandidateModels(context: Context): List<String> {
        val override = SecurePreferences(context).getGeminiModelOverride().trim()
        val list = mutableListOf<String>()
        if (override.isNotEmpty()) {
            list.add(override)
        }
        for (model in FALLBACK_MODELS) {
            if (!list.contains(model)) {
                list.add(model)
            }
        }
        return list
    }
}

/**
 * Thrown when Gemini returns HTTP 404 or an error indicating the model is unavailable or retired.
 */
class GeminiModelNotFoundException(
    val model: String,
    val httpCode: Int,
    val details: String
) : IOException("Gemini model '$model' is unavailable (HTTP $httpCode): $details")
