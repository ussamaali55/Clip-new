package com.clipgenius.ai.aiplanner

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.clipgenius.ai.data.SecurePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GeminiConfigTest {

    private lateinit var context: Context
    private lateinit var securePreferences: SecurePreferences

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        securePreferences = SecurePreferences(context)
        securePreferences.saveGeminiModelOverride("")
    }

    @Test
    fun testDefaultModelConstants() {
        assertEquals("gemini-3.8-flash", GeminiConfig.MODEL_NAME)
        assertEquals("gemini-3.8-flash", GeminiConfig.DEFAULT_MODEL)
        assertEquals("gemini-3.5-flash", GeminiConfig.FALLBACK_MODEL)
        assertEquals(listOf("gemini-3.8-flash", "gemini-3.5-flash"), GeminiConfig.FALLBACK_MODELS)
    }

    @Test
    fun testCandidateModelsDefault() {
        val candidates = GeminiConfig.getCandidateModels(context)
        assertEquals(2, candidates.size)
        assertEquals("gemini-3.8-flash", candidates[0])
        assertEquals("gemini-3.5-flash", candidates[1])
    }

    @Test
    fun testCandidateModelsWithUserOverride() {
        securePreferences.saveGeminiModelOverride("gemini-custom-model")
        val candidates = GeminiConfig.getCandidateModels(context)
        assertEquals(3, candidates.size)
        assertEquals("gemini-custom-model", candidates[0])
        assertEquals("gemini-3.8-flash", candidates[1])
        assertEquals("gemini-3.5-flash", candidates[2])
    }

    @Test
    fun testCandidateModelsWithOverrideMatchingFallback() {
        // If user overrides with fallback model, no duplicates should appear
        securePreferences.saveGeminiModelOverride("gemini-3.5-flash")
        val candidates = GeminiConfig.getCandidateModels(context)
        assertEquals(2, candidates.size)
        assertEquals("gemini-3.5-flash", candidates[0])
        assertEquals("gemini-3.8-flash", candidates[1])
    }

    @Test
    fun testEndpointUrlGeneration() {
        val url = GeminiConfig.getEndpointUrl("gemini-3.8-flash", "test-key-123")
        assertTrue(url.contains("gemini-3.8-flash:generateContent?key=test-key-123"))
        assertTrue(url.startsWith("https://generativelanguage.googleapis.com/v1beta/models/"))
    }
}
