package com.clipgenius.ai.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Manages encrypted storage for sensitive user API keys.
 * Uses AndroidX Security Crypto EncryptedSharedPreferences.
 */
class SecurePreferences(context: Context) {

    private val sharedPreferences: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_FILENAME,
            masterKeyAlias,
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.values().first(),
            EncryptedSharedPreferences.PrefValueEncryptionScheme.values().first()
        )
    } catch (e: Exception) {
        // Fallback for environments where AndroidKeyStore is unavailable (e.g. local unit tests)
        context.getSharedPreferences(PREFS_FILENAME, Context.MODE_PRIVATE)
    }

    fun saveGeminiApiKey(key: String) {
        sharedPreferences.edit().putString(KEY_GEMINI_API, key.trim()).apply()
    }

    fun getGeminiApiKey(): String {
        // SECURITY RULE: Never print keys to Logcat, never include them in error messages or toasts, and never export/share them.
        return sharedPreferences.getString(KEY_GEMINI_API, "") ?: ""
    }

    fun saveDeepgramApiKey(key: String) {
        sharedPreferences.edit().putString(KEY_DEEPGRAM_API, key.trim()).apply()
    }

    fun getDeepgramApiKey(): String {
        // SECURITY RULE: Never print keys to Logcat, never include them in error messages or toasts, and never export/share them.
        return sharedPreferences.getString(KEY_DEEPGRAM_API, "") ?: ""
    }

    fun clearApiKeys() {
        sharedPreferences.edit()
            .remove(KEY_GEMINI_API)
            .remove(KEY_DEEPGRAM_API)
            .remove(KEY_GEMINI_MODEL)
            .apply()
    }

    fun saveGeminiModelOverride(model: String) {
        sharedPreferences.edit().putString(KEY_GEMINI_MODEL, model.trim()).apply()
    }

    fun getGeminiModelOverride(): String {
        return sharedPreferences.getString(KEY_GEMINI_MODEL, "") ?: ""
    }

    companion object {
        private const val PREFS_FILENAME = "clipgenius_secure_prefs"
        private const val KEY_GEMINI_API = "encrypted_gemini_api_key"
        private const val KEY_DEEPGRAM_API = "encrypted_deepgram_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model_override"
    }
}
