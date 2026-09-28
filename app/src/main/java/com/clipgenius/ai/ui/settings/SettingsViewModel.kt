package com.clipgenius.ai.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.clipgenius.ai.aiplanner.GeminiConfig
import com.clipgenius.ai.data.SecurePreferences
import com.clipgenius.ai.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed class KeyTestResult {
    object Idle : KeyTestResult()
    object Testing : KeyTestResult()
    data class Success(val message: String) : KeyTestResult()
    data class Error(val message: String) : KeyTestResult()
}

class SettingsViewModel(private val securePreferences: SecurePreferences) : ViewModel() {

    private val _geminiKey = MutableStateFlow("")
    val geminiKey: StateFlow<String> = _geminiKey.asStateFlow()

    private val _deepgramKey = MutableStateFlow("")
    val deepgramKey: StateFlow<String> = _deepgramKey.asStateFlow()

    private val _geminiModel = MutableStateFlow(GeminiConfig.DEFAULT_MODEL)
    val geminiModel: StateFlow<String> = _geminiModel.asStateFlow()

    private val _saveConfirmationMessage = MutableStateFlow<String?>(null)
    val saveConfirmationMessage: StateFlow<String?> = _saveConfirmationMessage.asStateFlow()

    private val _geminiTestStatus = MutableStateFlow<KeyTestResult>(KeyTestResult.Idle)
    val geminiTestStatus: StateFlow<KeyTestResult> = _geminiTestStatus.asStateFlow()

    private val _deepgramTestStatus = MutableStateFlow<KeyTestResult>(KeyTestResult.Idle)
    val deepgramTestStatus: StateFlow<KeyTestResult> = _deepgramTestStatus.asStateFlow()

    private val _errorLogContent = MutableStateFlow("")
    val errorLogContent: StateFlow<String> = _errorLogContent.asStateFlow()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    init {
        loadKeys()
    }

    fun loadKeys() {
        // SECURITY RULE: Never print keys to Logcat, never include them in error messages or toasts, and never export/share them.
        _geminiKey.value = securePreferences.getGeminiApiKey()
        _deepgramKey.value = securePreferences.getDeepgramApiKey()
        val savedModel = securePreferences.getGeminiModelOverride().trim()
        _geminiModel.value = if (savedModel.isNotEmpty()) savedModel else GeminiConfig.DEFAULT_MODEL
    }

    fun updateGeminiKey(key: String) {
        _geminiKey.value = key
        _geminiTestStatus.value = KeyTestResult.Idle
        _saveConfirmationMessage.value = null
    }

    fun updateDeepgramKey(key: String) {
        _deepgramKey.value = key
        _deepgramTestStatus.value = KeyTestResult.Idle
        _saveConfirmationMessage.value = null
    }

    fun updateGeminiModel(model: String) {
        _geminiModel.value = model
        _saveConfirmationMessage.value = null
    }

    fun resetGeminiModel() {
        _geminiModel.value = GeminiConfig.DEFAULT_MODEL
        _saveConfirmationMessage.value = null
    }

    fun saveKeys() {
        securePreferences.saveGeminiApiKey(_geminiKey.value)
        securePreferences.saveDeepgramApiKey(_deepgramKey.value)
        val modelInput = _geminiModel.value.trim()
        if (modelInput == GeminiConfig.DEFAULT_MODEL) {
            securePreferences.saveGeminiModelOverride("")
        } else {
            securePreferences.saveGeminiModelOverride(modelInput)
        }
        _saveConfirmationMessage.value = "Settings saved securely!"
    }

    fun deleteKeys() {
        securePreferences.clearApiKeys()
        _geminiKey.value = ""
        _deepgramKey.value = ""
        _geminiModel.value = GeminiConfig.DEFAULT_MODEL
        _geminiTestStatus.value = KeyTestResult.Idle
        _deepgramTestStatus.value = KeyTestResult.Idle
        _saveConfirmationMessage.value = "All stored settings have been cleared."
    }

    fun testGeminiKey() {
        val key = _geminiKey.value.trim()
        if (key.isBlank()) {
            _geminiTestStatus.value = KeyTestResult.Error("Please enter a Gemini API key first.")
            return
        }

        _geminiTestStatus.value = KeyTestResult.Testing
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // Save key to vault first
                    securePreferences.saveGeminiApiKey(key)
                    // Minimal lightweight API call: List models with pageSize 1
                    val url = "https://generativelanguage.googleapis.com/v1beta/models?key=$key&pageSize=1"
                    val request = Request.Builder().url(url).get().build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            KeyTestResult.Success("Gemini API key is valid and connected!")
                        } else {
                            KeyTestResult.Error("Connection failed (HTTP ${response.code}): Invalid Gemini key.")
                        }
                    }
                } catch (e: Exception) {
                    KeyTestResult.Error("Network error: Unable to reach Gemini API.")
                }
            }
            _geminiTestStatus.value = result
        }
    }

    fun testDeepgramKey() {
        val key = _deepgramKey.value.trim()
        if (key.isBlank()) {
            _deepgramTestStatus.value = KeyTestResult.Error("Please enter a Deepgram API key first.")
            return
        }

        _deepgramTestStatus.value = KeyTestResult.Testing
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // Save key to vault first
                    securePreferences.saveDeepgramApiKey(key)
                    // Minimal lightweight API call: Get Deepgram projects
                    val url = "https://api.deepgram.com/v1/projects"
                    val request = Request.Builder()
                        .url(url)
                        .header("Authorization", "Token $key")
                        .get()
                        .build()
                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            KeyTestResult.Success("Deepgram API key is valid and connected!")
                        } else {
                            KeyTestResult.Error("Connection failed (HTTP ${response.code}): Invalid Deepgram key.")
                        }
                    }
                } catch (e: Exception) {
                    KeyTestResult.Error("Network error: Unable to reach Deepgram API.")
                }
            }
            _deepgramTestStatus.value = result
        }
    }

    fun clearConfirmationMessage() {
        _saveConfirmationMessage.value = null
    }

    fun loadErrorLog(context: Context) {
        _errorLogContent.value = CrashLogger.getLogContent(context)
    }

    fun clearErrorLog(context: Context) {
        CrashLogger.clearLog(context)
        _errorLogContent.value = CrashLogger.getLogContent(context)
    }

    fun copyErrorLog(context: Context) {
        CrashLogger.copyToClipboard(context)
    }
}

class SettingsViewModelFactory(private val securePreferences: SecurePreferences) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(securePreferences) as T
    }
}
