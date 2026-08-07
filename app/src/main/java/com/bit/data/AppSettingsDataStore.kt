package com.bit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bit.global.PerformanceMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppSettingsDataStore(private val context: Context) {

    companion object {
        private val STREAMING_ENABLED = booleanPreferencesKey("streaming_enabled")
        private val CHAT_MEMORY_ENABLED = booleanPreferencesKey("chat_memory_enabled")
        private val TOOL_CALLING_ENABLED = booleanPreferencesKey("tool_calling_enabled")
        private val TOOL_CALLING_BYPASS_ENABLED = booleanPreferencesKey("tool_calling_bypass_enabled")
        private val IMAGE_BLUR_ENABLED = booleanPreferencesKey("image_blur_enabled")
        private val LOAD_TTS_ON_START = booleanPreferencesKey("load_tts_on_start")
        private val CODE_HIGHLIGHT_ENABLED = booleanPreferencesKey("code_highlight_enabled")
        private val LAST_CHAT_ID = stringPreferencesKey("last_chat_id")
        private val LAST_MODEL_ID = stringPreferencesKey("last_model_id")
        private val ACTIVE_PERSONA_ID = stringPreferencesKey("active_persona_id")
        private val AI_MEMORY_ENABLED = booleanPreferencesKey("ai_memory_enabled")
        private val SECURITY_MODE = stringPreferencesKey("security_mode")
        private val GUIDE_SEEN = booleanPreferencesKey("showcase_seen") // key kept for backward compat
        private val HARDWARE_PROFILE_JSON = stringPreferencesKey("hardware_profile_json")
        private val HARDWARE_TUNING_ENABLED = booleanPreferencesKey("hardware_tuning_enabled")
        private val PERFORMANCE_MODE = stringPreferencesKey("performance_mode")
        private val ASK_MODEL_RELOAD_DIALOG = booleanPreferencesKey("ask_model_reload_dialog")
        private val HUGGING_FACE_TOKEN = stringPreferencesKey("hugging_face_token")
        private val PROFILE_NAME = stringPreferencesKey("profile_name")
        private val PROFILE_EMAIL = stringPreferencesKey("profile_email")
        private val PROFILE_PHONE = stringPreferencesKey("profile_phone")
        private val SPEED_MODE_ENABLED = booleanPreferencesKey("speed_mode_enabled")
        private val STT_THREADS = androidx.datastore.preferences.core.intPreferencesKey("stt_threads")
        private val STT_LANGUAGE = stringPreferencesKey("stt_language")
        private val LOCAL_SERVER_ENABLED = booleanPreferencesKey("local_server_enabled")
        private val LOCAL_SERVER_PORT = androidx.datastore.preferences.core.intPreferencesKey("local_server_port")
        private val LOCAL_SERVER_TOKEN = stringPreferencesKey("local_server_token")
        private val WEB_SEARCH_PROVIDER = stringPreferencesKey("web_search_provider")
        private val WEB_SEARCH_API_KEY = stringPreferencesKey("web_search_api_key")
        private val WEB_SEARCH_BASE_URL = stringPreferencesKey("web_search_base_url")
        private val GLOBAL_SYSTEM_PROMPT = stringPreferencesKey("global_system_prompt")
        private val GLOBAL_PREPEND_PROMPT = stringPreferencesKey("global_prepend_prompt")
        private val GLOBAL_POSTPEND_PROMPT = stringPreferencesKey("global_postpend_prompt")
        private val HAS_SEEN_MEMORY_IMPORT_PROMPT = booleanPreferencesKey("has_seen_memory_import_prompt")
    }

    val localServerEnabled: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[LOCAL_SERVER_ENABLED] ?: false
    }

    suspend fun updateLocalServerEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[LOCAL_SERVER_ENABLED] = enabled }
    }

    val localServerPort: Flow<Int> = context.appSettingsDataStore.data.map { prefs ->
        prefs[LOCAL_SERVER_PORT] ?: 8080
    }

    suspend fun updateLocalServerPort(port: Int) {
        context.appSettingsDataStore.edit { it[LOCAL_SERVER_PORT] = port }
    }

    val localServerToken: Flow<String> = context.appSettingsDataStore.data.map { prefs ->
        prefs[LOCAL_SERVER_TOKEN] ?: ""
    }

    suspend fun updateLocalServerToken(token: String) {
        context.appSettingsDataStore.edit { it[LOCAL_SERVER_TOKEN] = token }
    }

    val sttThreads: Flow<Int> = context.appSettingsDataStore.data.map { prefs ->
        prefs[STT_THREADS] ?: 2
    }

    suspend fun updateSttThreads(threads: Int) {
        context.appSettingsDataStore.edit { it[STT_THREADS] = threads.coerceIn(1, 4) }
    }

    val sttLanguage: Flow<String> = context.appSettingsDataStore.data.map { prefs ->
        prefs[STT_LANGUAGE] ?: "en"
    }

    suspend fun updateSttLanguage(language: String) {
        context.appSettingsDataStore.edit { it[STT_LANGUAGE] = language }
    }

    val speedModeEnabled: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[SPEED_MODE_ENABLED] ?: false
    }

    suspend fun updateSpeedModeEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[SPEED_MODE_ENABLED] = enabled }
    }

    val streamingEnabled: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[STREAMING_ENABLED] ?: true
    }

    val chatMemoryEnabled: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[CHAT_MEMORY_ENABLED] ?: true
    }

    val toolCallingEnabled: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[TOOL_CALLING_ENABLED] ?: true
    }

    val toolCallingBypassEnabled: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[TOOL_CALLING_BYPASS_ENABLED] ?: false
    }

    val imageBlurEnabled: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[IMAGE_BLUR_ENABLED] ?: true
    }

    val loadTTSOnStart: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[LOAD_TTS_ON_START] ?: true
    }

    val codeHighlightEnabled: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[CODE_HIGHLIGHT_ENABLED] ?: true
    }

    suspend fun updateStreamingEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[STREAMING_ENABLED] = enabled }
    }

    suspend fun updateChatMemoryEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[CHAT_MEMORY_ENABLED] = enabled }
    }

    suspend fun updateToolCallingEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[TOOL_CALLING_ENABLED] = enabled }
    }

    suspend fun updateToolCallingBypassEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[TOOL_CALLING_BYPASS_ENABLED] = enabled }
    }

    suspend fun updateImageBlurEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[IMAGE_BLUR_ENABLED] = enabled }
    }

    suspend fun updateLoadTTSOnStart(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[LOAD_TTS_ON_START] = enabled }
    }

    suspend fun updateCodeHighlightEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[CODE_HIGHLIGHT_ENABLED] = enabled }
    }

    val lastChatId: Flow<String?> = context.appSettingsDataStore.data.map { prefs ->
        prefs[LAST_CHAT_ID]
    }

    suspend fun saveLastChatId(chatId: String?) {
        context.appSettingsDataStore.edit { prefs ->
            if (chatId != null) {
                prefs[LAST_CHAT_ID] = chatId
            } else {
                prefs.remove(LAST_CHAT_ID)
            }
        }
    }

    val lastModelId: Flow<String?> = context.appSettingsDataStore.data.map { prefs ->
        prefs[LAST_MODEL_ID]
    }

    suspend fun saveLastModelId(modelId: String?) {
        context.appSettingsDataStore.edit { prefs ->
            if (modelId != null) {
                prefs[LAST_MODEL_ID] = modelId
            } else {
                prefs.remove(LAST_MODEL_ID)
            }
        }
    }

    val activePersonaId: Flow<String?> = context.appSettingsDataStore.data.map { prefs ->
        prefs[ACTIVE_PERSONA_ID]
    }

    suspend fun saveActivePersonaId(personaId: String?) {
        context.appSettingsDataStore.edit { prefs ->
            if (personaId != null) {
                prefs[ACTIVE_PERSONA_ID] = personaId
            } else {
                prefs.remove(ACTIVE_PERSONA_ID)
            }
        }
    }

    val aiMemoryEnabled: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[AI_MEMORY_ENABLED] ?: true
    }

    suspend fun updateAiMemoryEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[AI_MEMORY_ENABLED] = enabled }
    }

    val globalSystemPrompt: Flow<String> = context.appSettingsDataStore.data.map { prefs ->
        val raw = prefs[GLOBAL_SYSTEM_PROMPT] ?: PredefinedVariables.compile(
            DefaultSystemPrompt.create().systemItems,
            emptyMap(),
            emptyMap() // Keep {variables} as literal strings for the editor
        )
        raw.replace("2026-05-10", "{date}")
           .replace("14:30:00", "{time}")
    }

    suspend fun updateGlobalSystemPrompt(prompt: String) {
        context.appSettingsDataStore.edit { it[GLOBAL_SYSTEM_PROMPT] = prompt }
    }

    val globalPrependPrompt: Flow<String> = context.appSettingsDataStore.data.map { prefs ->
        prefs[GLOBAL_PREPEND_PROMPT] ?: PredefinedVariables.compile(
            DefaultSystemPrompt.create().userPrependItems,
            emptyMap(),
            emptyMap()
        )
    }

    suspend fun updateGlobalPrependPrompt(prompt: String) {
        context.appSettingsDataStore.edit { it[GLOBAL_PREPEND_PROMPT] = prompt }
    }

    val globalPostpendPrompt: Flow<String> = context.appSettingsDataStore.data.map { prefs ->
        prefs[GLOBAL_POSTPEND_PROMPT] ?: PredefinedVariables.compile(
            DefaultSystemPrompt.create().userPostpendItems,
            emptyMap(),
            emptyMap()
        )
    }

    suspend fun updateGlobalPostpendPrompt(prompt: String) {
        context.appSettingsDataStore.edit { it[GLOBAL_POSTPEND_PROMPT] = prompt }
    }

    val securityMode: Flow<String> = context.appSettingsDataStore.data.map { prefs ->
        prefs[SECURITY_MODE] ?: "REGULAR"
    }

    suspend fun saveSecurityMode(mode: String) {
        context.appSettingsDataStore.edit { it[SECURITY_MODE] = mode }
    }

    val hasSeenMemoryImportPrompt: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[HAS_SEEN_MEMORY_IMPORT_PROMPT] ?: false
    }

    suspend fun saveHasSeenMemoryImportPrompt(seen: Boolean) {
        context.appSettingsDataStore.edit { it[HAS_SEEN_MEMORY_IMPORT_PROMPT] = seen }
    }

    val guideSeen: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[GUIDE_SEEN] ?: false
    }

    suspend fun saveGuideSeen(seen: Boolean) {
        context.appSettingsDataStore.edit { it[GUIDE_SEEN] = seen }
    }

    val hardwareProfileJson: Flow<String?> = context.appSettingsDataStore.data.map { prefs ->
        prefs[HARDWARE_PROFILE_JSON]
    }

    val hardwareTuningEnabled: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[HARDWARE_TUNING_ENABLED] ?: true
    }

    suspend fun saveHardwareProfile(json: String) {
        context.appSettingsDataStore.edit { it[HARDWARE_PROFILE_JSON] = json }
    }

    suspend fun updateHardwareTuningEnabled(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[HARDWARE_TUNING_ENABLED] = enabled }
    }

    val performanceMode: Flow<PerformanceMode> = context.appSettingsDataStore.data.map { prefs ->
        val name = prefs[PERFORMANCE_MODE] ?: PerformanceMode.BALANCED.name
        try { PerformanceMode.valueOf(name) } catch (_: Exception) { PerformanceMode.BALANCED }
    }

    suspend fun savePerformanceMode(mode: PerformanceMode) {
        context.appSettingsDataStore.edit { it[PERFORMANCE_MODE] = mode.name }
    }

    val askModelReloadDialog: Flow<Boolean> = context.appSettingsDataStore.data.map { prefs ->
        prefs[ASK_MODEL_RELOAD_DIALOG] ?: true
    }

    suspend fun updateAskModelReloadDialog(enabled: Boolean) {
        context.appSettingsDataStore.edit { it[ASK_MODEL_RELOAD_DIALOG] = enabled }
    }

    val huggingFaceToken: Flow<String?> = context.appSettingsDataStore.data.map { prefs ->
        prefs[HUGGING_FACE_TOKEN]
    }

    suspend fun saveHuggingFaceToken(token: String?) {
        context.appSettingsDataStore.edit { prefs ->
            if (token != null) {
                prefs[HUGGING_FACE_TOKEN] = token
            } else {
                prefs.remove(HUGGING_FACE_TOKEN)
            }
        }
    }

    val profileName: Flow<String> = context.appSettingsDataStore.data.map { prefs ->
        prefs[PROFILE_NAME] ?: ""
    }

    suspend fun saveProfileName(name: String) {
        context.appSettingsDataStore.edit { it[PROFILE_NAME] = name }
    }

    val profileEmail: Flow<String> = context.appSettingsDataStore.data.map { prefs ->
        prefs[PROFILE_EMAIL] ?: ""
    }

    suspend fun saveProfileEmail(email: String) {
        context.appSettingsDataStore.edit { it[PROFILE_EMAIL] = email }
    }

    val profilePhone: Flow<String> = context.appSettingsDataStore.data.map { prefs ->
        prefs[PROFILE_PHONE] ?: ""
    }

    suspend fun saveProfilePhone(phone: String) {
        context.appSettingsDataStore.edit { it[PROFILE_PHONE] = phone }
    }

    val webSearchProvider: Flow<String> = context.appSettingsDataStore.data.map { prefs ->
        prefs[WEB_SEARCH_PROVIDER] ?: "duckduckgo"
    }

    suspend fun saveWebSearchProvider(provider: String) {
        context.appSettingsDataStore.edit { it[WEB_SEARCH_PROVIDER] = provider }
    }

    val webSearchApiKey: Flow<String> = context.appSettingsDataStore.data.map { prefs ->
        prefs[WEB_SEARCH_API_KEY] ?: ""
    }

    suspend fun saveWebSearchApiKey(key: String) {
        context.appSettingsDataStore.edit { it[WEB_SEARCH_API_KEY] = key }
    }

    val webSearchBaseUrl: Flow<String> = context.appSettingsDataStore.data.map { prefs ->
        prefs[WEB_SEARCH_BASE_URL] ?: ""
    }

    suspend fun saveWebSearchBaseUrl(url: String) {
        context.appSettingsDataStore.edit { it[WEB_SEARCH_BASE_URL] = url }
    }

    suspend fun clear() {
        context.appSettingsDataStore.edit { it.clear() }
    }
}
