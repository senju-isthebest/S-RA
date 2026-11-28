package com.example.core

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.ai.AiProviderConfig
import com.example.data.ai.AiRoutingStrategy
import com.example.ui.theme.SoraThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.soraDataStore: DataStore<Preferences> by preferencesDataStore(name = "sora_prefs")

data class SoraSettings(
    val themeMode: SoraThemeMode = SoraThemeMode.DARK,
    val languageCode: String = "",
    val appLanguage: AppLanguage = AppLanguage.resolve(null),
    val pomodoroWorkMinutes: Int = 25,
    val pomodoroShortBreakMinutes: Int = 5,
    val pomodoroLongBreakMinutes: Int = 15,
    val ambientFocusSound: Boolean = false,
    val aiStrategy: AiRoutingStrategy = AiRoutingStrategy.GEMINI_FIRST,
    val aiFallbackEnabled: Boolean = true,
    val ollamaEndpoint: String = AiProviderConfig.DEFAULT_OLLAMA_ENDPOINT,
    val ollamaModel: String = AiProviderConfig.DEFAULT_OLLAMA_MODEL,
    val ollamaVisionModel: String = "",
    val geminiModel: String = AiProviderConfig.DEFAULT_GEMINI_MODEL,
    val geminiApiKey: String = "",
    val aiTimeoutMs: Long = AiProviderConfig.DEFAULT_TIMEOUT_MS,
    val localOnlyPrivacyMode: Boolean = false,
    val updatedAtMillis: Long = 0L
) {
    fun toAiProviderConfig(): AiProviderConfig = AiProviderConfig(
        strategy = aiStrategy,
        fallbackEnabled = aiFallbackEnabled,
        ollamaEndpoint = ollamaEndpoint.ifBlank { AiProviderConfig.DEFAULT_OLLAMA_ENDPOINT },
        ollamaModel = ollamaModel.ifBlank { AiProviderConfig.DEFAULT_OLLAMA_MODEL },
        ollamaVisionModel = ollamaVisionModel.trim(),
        geminiModel = geminiModel.ifBlank { AiProviderConfig.DEFAULT_GEMINI_MODEL },
        geminiApiKey = geminiApiKey.trim(),
        requestTimeoutMs = aiTimeoutMs.coerceIn(5_000L, 180_000L),
        localOnlyPrivacyMode = localOnlyPrivacyMode
    )
}

class UserPreferences(private val context: Context) {
    private object Keys {
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val LANGUAGE_CODE = stringPreferencesKey("app_language_code")
        val POMODORO_WORK = intPreferencesKey("pomodoro_work")
        val POMODORO_SHORT = intPreferencesKey("pomodoro_short")
        val POMODORO_LONG = intPreferencesKey("pomodoro_long")
        val AMBIENT_SOUND = booleanPreferencesKey("ambient_sound")
        val AI_STRATEGY = stringPreferencesKey("ai_routing_strategy")
        val AI_FALLBACK = booleanPreferencesKey("ai_fallback_enabled")
        val OLLAMA_ENDPOINT = stringPreferencesKey("ollama_endpoint")
        val OLLAMA_MODEL = stringPreferencesKey("ollama_model")
        val OLLAMA_VISION_MODEL = stringPreferencesKey("ollama_vision_model")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        val AI_TIMEOUT_MS = longPreferencesKey("ai_timeout_ms")
        val LOCAL_ONLY_PRIVACY = booleanPreferencesKey("local_only_privacy_mode")
        val UPDATED_AT_MILLIS = longPreferencesKey("prefs_updated_at_millis")
    }

    val settings: Flow<SoraSettings> = context.soraDataStore.data.map { prefs ->
        val modeName = prefs[Keys.THEME_MODE] ?: SoraThemeMode.DARK.name
        val mode = SoraThemeMode.entries.firstOrNull { it.name == modeName } ?: SoraThemeMode.DARK

        val savedLangCode = prefs[Keys.LANGUAGE_CODE].orEmpty()
        val resolvedLang = AppLanguage.resolve(savedLangCode.ifBlank { null })

        val strategyName = prefs[Keys.AI_STRATEGY] ?: AiRoutingStrategy.GEMINI_FIRST.name
        val strategy = AiRoutingStrategy.entries.firstOrNull { it.name == strategyName }
            ?: AiRoutingStrategy.GEMINI_FIRST

        val storedKey = prefs[Keys.GEMINI_API_KEY] ?: ""
        val decryptedKey = EncryptedSecretBox.decrypt(storedKey)

        SoraSettings(
            themeMode = mode,
            languageCode = savedLangCode,
            appLanguage = resolvedLang,
            pomodoroWorkMinutes = prefs[Keys.POMODORO_WORK] ?: 25,
            pomodoroShortBreakMinutes = prefs[Keys.POMODORO_SHORT] ?: 5,
            pomodoroLongBreakMinutes = prefs[Keys.POMODORO_LONG] ?: 15,
            ambientFocusSound = prefs[Keys.AMBIENT_SOUND] ?: false,
            aiStrategy = strategy,
            aiFallbackEnabled = prefs[Keys.AI_FALLBACK] ?: true,
            ollamaEndpoint = prefs[Keys.OLLAMA_ENDPOINT] ?: AiProviderConfig.DEFAULT_OLLAMA_ENDPOINT,
            ollamaModel = prefs[Keys.OLLAMA_MODEL] ?: AiProviderConfig.DEFAULT_OLLAMA_MODEL,
            ollamaVisionModel = prefs[Keys.OLLAMA_VISION_MODEL] ?: "",
            geminiModel = prefs[Keys.GEMINI_MODEL] ?: AiProviderConfig.DEFAULT_GEMINI_MODEL,
            geminiApiKey = decryptedKey,
            aiTimeoutMs = prefs[Keys.AI_TIMEOUT_MS] ?: AiProviderConfig.DEFAULT_TIMEOUT_MS,
            localOnlyPrivacyMode = prefs[Keys.LOCAL_ONLY_PRIVACY] ?: false,
            updatedAtMillis = prefs[Keys.UPDATED_AT_MILLIS] ?: 0L
        )
    }

    suspend fun setThemeMode(mode: SoraThemeMode) {
        context.soraDataStore.edit {
            it[Keys.THEME_MODE] = mode.name
            it[Keys.UPDATED_AT_MILLIS] = System.currentTimeMillis()
        }
    }

    suspend fun setAppLanguage(language: AppLanguage) {
        context.soraDataStore.edit {
            it[Keys.LANGUAGE_CODE] = language.code
            it[Keys.UPDATED_AT_MILLIS] = System.currentTimeMillis()
        }
    }

    suspend fun setPomodoroDurations(work: Int, shortBreak: Int, longBreak: Int) {
        context.soraDataStore.edit {
            it[Keys.POMODORO_WORK] = work.coerceIn(5, 120)
            it[Keys.POMODORO_SHORT] = shortBreak.coerceIn(1, 30)
            it[Keys.POMODORO_LONG] = longBreak.coerceIn(5, 60)
            it[Keys.UPDATED_AT_MILLIS] = System.currentTimeMillis()
        }
    }

    suspend fun setAmbientSound(enabled: Boolean) {
        context.soraDataStore.edit {
            it[Keys.AMBIENT_SOUND] = enabled
            it[Keys.UPDATED_AT_MILLIS] = System.currentTimeMillis()
        }
    }

    suspend fun setLocalOnlyPrivacyMode(enabled: Boolean) {
        context.soraDataStore.edit {
            it[Keys.LOCAL_ONLY_PRIVACY] = enabled
            it[Keys.UPDATED_AT_MILLIS] = System.currentTimeMillis()
        }
    }

    suspend fun updateAiSettings(config: AiProviderConfig) {
        val encryptedKey = EncryptedSecretBox.encrypt(config.geminiApiKey)
        context.soraDataStore.edit {
            it[Keys.AI_STRATEGY] = config.strategy.name
            it[Keys.AI_FALLBACK] = config.fallbackEnabled
            it[Keys.OLLAMA_ENDPOINT] = config.ollamaEndpoint.trim().ifBlank { AiProviderConfig.DEFAULT_OLLAMA_ENDPOINT }
            it[Keys.OLLAMA_MODEL] = config.ollamaModel.trim().ifBlank { AiProviderConfig.DEFAULT_OLLAMA_MODEL }
            it[Keys.OLLAMA_VISION_MODEL] = config.ollamaVisionModel.trim()
            it[Keys.GEMINI_MODEL] = config.geminiModel.trim().ifBlank { AiProviderConfig.DEFAULT_GEMINI_MODEL }
            it[Keys.GEMINI_API_KEY] = encryptedKey
            it[Keys.AI_TIMEOUT_MS] = config.requestTimeoutMs.coerceIn(5_000L, 180_000L)
            it[Keys.UPDATED_AT_MILLIS] = System.currentTimeMillis()
        }
    }

    /**
     * Merges non-secret preferences synced from another device using Last-Write-Wins timestamps.
     * Never modifies device-local API keys or local-only endpoint URIs.
     */
    suspend fun applySyncedNonSecretPreferences(
        themeModeName: String,
        languageCode: String,
        pomodoroWork: Int,
        pomodoroShort: Int,
        pomodoroLong: Int,
        ambientSound: Boolean,
        aiStrategyName: String,
        aiFallbackEnabled: Boolean,
        ollamaModel: String,
        geminiModel: String,
        aiTimeoutMs: Long,
        remoteUpdatedAtMillis: Long
    ) {
        context.soraDataStore.edit {
            val localUpdated = it[Keys.UPDATED_AT_MILLIS] ?: 0L
            if (remoteUpdatedAtMillis <= localUpdated) return@edit
            if (SoraThemeMode.entries.any { m -> m.name == themeModeName }) {
                it[Keys.THEME_MODE] = themeModeName
            }
            if (languageCode.isNotBlank()) {
                it[Keys.LANGUAGE_CODE] = languageCode
            }
            it[Keys.POMODORO_WORK] = pomodoroWork.coerceIn(5, 120)
            it[Keys.POMODORO_SHORT] = pomodoroShort.coerceIn(1, 30)
            it[Keys.POMODORO_LONG] = pomodoroLong.coerceIn(5, 60)
            it[Keys.AMBIENT_SOUND] = ambientSound
            if (AiRoutingStrategy.entries.any { s -> s.name == aiStrategyName }) {
                it[Keys.AI_STRATEGY] = aiStrategyName
            }
            it[Keys.AI_FALLBACK] = aiFallbackEnabled
            if (ollamaModel.isNotBlank()) it[Keys.OLLAMA_MODEL] = ollamaModel.trim()
            if (geminiModel.isNotBlank()) it[Keys.GEMINI_MODEL] = geminiModel.trim()
            it[Keys.AI_TIMEOUT_MS] = aiTimeoutMs.coerceIn(5_000L, 180_000L)
            it[Keys.UPDATED_AT_MILLIS] = remoteUpdatedAtMillis
        }
    }

    suspend fun clearGeminiApiKey() {
        context.soraDataStore.edit {
            it[Keys.GEMINI_API_KEY] = ""
            it[Keys.UPDATED_AT_MILLIS] = System.currentTimeMillis()
        }
    }

    suspend fun clearAllPreferences() {
        context.soraDataStore.edit { it.clear() }
    }
}
