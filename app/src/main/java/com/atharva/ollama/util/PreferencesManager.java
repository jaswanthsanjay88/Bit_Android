package com.atharva.ollama.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

import com.atharva.ollama.R;
import com.atharva.ollama.api.ApiProvider;

/**
 * Preferences manager for app settings.
 */
public class PreferencesManager {

    private static final String PREFS_NAME = "AtharvaAISettings";
    
    // Keys
    public static final String KEY_API_PROVIDER = "api_provider";
    public static final String KEY_SERVER_IP = "server_ip";
    public static final String KEY_PORT = "port";
    public static final String KEY_CUSTOM_BASE_URL = "custom_base_url";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_MODEL = "model";
    public static final String KEY_SYSTEM_PROMPT = "system_prompt";
    public static final String KEY_DARK_MODE = "dark_mode";
    public static final String KEY_CURRENT_CONVERSATION = "current_conversation";
    public static final String KEY_STREAMING_ENABLED = "streaming_enabled";
    
    // Web Search Keys
    public static final String KEY_WEB_SEARCH_ENABLED = "web_search_enabled";
    public static final String KEY_SEARX_HOST = "searx_host";

    // Defaults
    public static final String DEFAULT_IP = "192.168.1.10";
    public static final String DEFAULT_PORT = "11434";
    public static final String DEFAULT_MODEL = "llama3";
    public static final String DEFAULT_SYSTEM_PROMPT = "You are Atharva AI, a helpful, intelligent, and friendly AI assistant created by Jaswanth Sanjay Nekkanti. You provide accurate, thoughtful responses while being conversational and engaging.";
    public static final String DEFAULT_SEARX_HOST = "https://searxng.site/"; // A public instance

    // Default models for different providers
    public static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";
    public static final String DEFAULT_OPENROUTER_MODEL = "openai/gpt-4o-mini";
    public static final String DEFAULT_GROQ_MODEL = "llama-3.3-70b-versatile";
    public static final String DEFAULT_TOGETHER_MODEL = "meta-llama/Llama-3.3-70B-Instruct-Turbo";
    public static final String DEFAULT_ANTHROPIC_MODEL = "claude-3-5-sonnet-20241022";
    
    // MediaPipe settings
    public static final String KEY_MEDIAPIPE_MODEL_PATH = "mediapipe_model_path";
    public static final String DEFAULT_MEDIAPIPE_PATH = "/data/local/tmp/llm/gemma3.task";
    public static final String DEFAULT_MEDIAPIPE_MODEL = "gemma-3-1b-it";
    
    // HuggingFace settings for model downloads
    public static final String KEY_HF_TOKEN = "hf_token";
    public static final String KEY_SELECTED_MODEL_INDEX = "selected_model_index";

    private final SharedPreferences prefs;

    public PreferencesManager(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // API Provider
    public ApiProvider getApiProvider() {
        String name = prefs.getString(KEY_API_PROVIDER, ApiProvider.OLLAMA.name());
        return ApiProvider.fromName(name);
    }

    public void setApiProvider(ApiProvider provider) {
        prefs.edit().putString(KEY_API_PROVIDER, provider.name()).apply();
    }

    // Server settings (for Ollama)
    public String getServerIp() {
        return prefs.getString(KEY_SERVER_IP, DEFAULT_IP);
    }

    public void setServerIp(String ip) {
        prefs.edit().putString(KEY_SERVER_IP, ip).apply();
    }

    public String getPort() {
        return prefs.getString(KEY_PORT, DEFAULT_PORT);
    }

    public void setPort(String port) {
        prefs.edit().putString(KEY_PORT, port).apply();
    }

    // Custom base URL (for custom provider)
    public String getCustomBaseUrl() {
        return prefs.getString(KEY_CUSTOM_BASE_URL, "");
    }

    public void setCustomBaseUrl(String url) {
        prefs.edit().putString(KEY_CUSTOM_BASE_URL, url).apply();
    }

    // API Key (for cloud providers)
    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public void setApiKey(String key) {
        prefs.edit().putString(KEY_API_KEY, key).apply();
    }

    /**
     * Get the base URL based on current provider.
     */
    public String getBaseUrl() {
        ApiProvider provider = getApiProvider();
        switch (provider) {
            case OLLAMA:
                return String.format("http://%s:%s/", getServerIp(), getPort());
            case CUSTOM:
                String customUrl = getCustomBaseUrl();
                if (!customUrl.endsWith("/")) customUrl += "/";
                return customUrl;
            default:
                return provider.getDefaultBaseUrl();
        }
    }

    // Model settings
    public String getModel() {
        return prefs.getString(KEY_MODEL, DEFAULT_MODEL);
    }

    public void setModel(String model) {
        prefs.edit().putString(KEY_MODEL, model).apply();
    }

    /**
     * Get default model for a provider.
     */
    public String getDefaultModelForProvider(ApiProvider provider) {
        switch (provider) {
            case OPENAI:
                return DEFAULT_OPENAI_MODEL;
            case OPENROUTER:
                return DEFAULT_OPENROUTER_MODEL;
            case GROQ:
                return DEFAULT_GROQ_MODEL;
            case TOGETHER:
                return DEFAULT_TOGETHER_MODEL;
            case ANTHROPIC:
                return DEFAULT_ANTHROPIC_MODEL;
            case MEDIAPIPE:
                return DEFAULT_MEDIAPIPE_MODEL;
            case OLLAMA:
            case CUSTOM:
            default:
                return DEFAULT_MODEL;
        }
    }
    
    // MediaPipe Model Path
    public String getMediaPipeModelPath() {
        return prefs.getString(KEY_MEDIAPIPE_MODEL_PATH, DEFAULT_MEDIAPIPE_PATH);
    }
    
    public void setMediaPipeModelPath(String path) {
        prefs.edit().putString(KEY_MEDIAPIPE_MODEL_PATH, path).apply();
    }
    
    // HuggingFace Token
    public String getHfToken() {
        return prefs.getString(KEY_HF_TOKEN, "");
    }
    
    public void setHfToken(String token) {
        prefs.edit().putString(KEY_HF_TOKEN, token).apply();
    }
    
    public int getSelectedModelIndex() {
        return prefs.getInt(KEY_SELECTED_MODEL_INDEX, 0);
    }
    
    public void setSelectedModelIndex(int index) {
        prefs.edit().putInt(KEY_SELECTED_MODEL_INDEX, index).apply();
    }

    public String getSystemPrompt() {
        return prefs.getString(KEY_SYSTEM_PROMPT, DEFAULT_SYSTEM_PROMPT);
    }

    public void setSystemPrompt(String prompt) {
        prefs.edit().putString(KEY_SYSTEM_PROMPT, prompt).apply();
    }

    // Web Search Settings
    public boolean isWebSearchEnabled() {
        return prefs.getBoolean(KEY_WEB_SEARCH_ENABLED, true);
    }

    public void setWebSearchEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_WEB_SEARCH_ENABLED, enabled).apply();
    }

    public String getSearxHost() {
        return prefs.getString(KEY_SEARX_HOST, DEFAULT_SEARX_HOST);
    }

    public void setSearxHost(String host) {
        prefs.edit().putString(KEY_SEARX_HOST, host).apply();
    }
    
    // Search Focus Category
    public static final String KEY_SEARCH_FOCUS = "search_focus";
    public static final String SEARCH_FOCUS_ALL = "all";
    public static final String SEARCH_FOCUS_REDDIT = "reddit";
    public static final String SEARCH_FOCUS_STACKOVERFLOW = "stackoverflow";
    public static final String SEARCH_FOCUS_ACADEMIC = "academic";
    public static final String SEARCH_FOCUS_YOUTUBE = "youtube";
    public static final String SEARCH_FOCUS_NEWS = "news";
    
    public String getSearchFocus() {
        return prefs.getString(KEY_SEARCH_FOCUS, SEARCH_FOCUS_ALL);
    }
    
    public void setSearchFocus(String focus) {
        prefs.edit().putString(KEY_SEARCH_FOCUS, focus).apply();
    }
    
    /**
     * Get the site filter for DuckDuckGo based on search focus.
     * Returns empty string for "all", or "site:reddit.com" etc for specific focus.
     */
    public String getSearchSiteFilter() {
        String focus = getSearchFocus();
        switch (focus) {
            case SEARCH_FOCUS_REDDIT:
                return "site:reddit.com";
            case SEARCH_FOCUS_STACKOVERFLOW:
                return "site:stackoverflow.com OR site:stackexchange.com";
            case SEARCH_FOCUS_ACADEMIC:
                return "site:arxiv.org OR site:scholar.google.com OR site:researchgate.net";
            case SEARCH_FOCUS_YOUTUBE:
                return "site:youtube.com";
            case SEARCH_FOCUS_NEWS:
                return "site:bbc.com OR site:cnn.com OR site:reuters.com OR site:theverge.com";
            case SEARCH_FOCUS_ALL:
            default:
                return "";
        }
    }

    // UI settings
    public boolean isDarkMode() {
        return prefs.getBoolean(KEY_DARK_MODE, false);
    }

    public void setDarkMode(boolean enabled) {
        prefs.edit().putBoolean(KEY_DARK_MODE, enabled).apply();
    }

    /**
     * Get the theme resource ID based on dark mode preference.
     */
    public int getThemeResId() {
        return isDarkMode() ? R.style.Theme_OllamaChat_Dark : R.style.Theme_OllamaChat;
    }
    
    // Conversation
    public long getCurrentConversationId() {
        return prefs.getLong(KEY_CURRENT_CONVERSATION, -1);
    }

    public void setCurrentConversationId(long id) {
        prefs.edit().putLong(KEY_CURRENT_CONVERSATION, id).apply();
    }

    // Streaming
    public boolean isStreamingEnabled() {
        return prefs.getBoolean(KEY_STREAMING_ENABLED, true);
    }

    public void setStreamingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_STREAMING_ENABLED, enabled).apply();
    }

    // Biometric Lock
    public static final String KEY_BIOMETRIC_ENABLED = "biometric_enabled";
    
    public boolean isBiometricEnabled() {
        return prefs.getBoolean(KEY_BIOMETRIC_ENABLED, false);
    }

    public void setBiometricEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BIOMETRIC_ENABLED, enabled).apply();
    }
}
