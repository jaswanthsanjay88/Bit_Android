package com.atharva.ollama.api;

/**
 * Enum representing different AI API providers.
 */
public enum ApiProvider {
    OLLAMA("Ollama (Local)", "http://localhost:11434/", false),
    MEDIAPIPE("On-Device (Gemma)", "", false),
    OPENAI("OpenAI", "https://api.openai.com/v1/", true),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1/", true),
    GROQ("Groq", "https://api.groq.com/openai/v1/", true),
    TOGETHER("Together AI", "https://api.together.xyz/v1/", true),
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com/v1/", true),
    CUSTOM("Custom (OpenAI Compatible)", "", true);

    private final String displayName;
    private final String defaultBaseUrl;
    private final boolean requiresApiKey;

    ApiProvider(String displayName, String defaultBaseUrl, boolean requiresApiKey) {
        this.displayName = displayName;
        this.defaultBaseUrl = defaultBaseUrl;
        this.requiresApiKey = requiresApiKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDefaultBaseUrl() {
        return defaultBaseUrl;
    }

    public boolean requiresApiKey() {
        return requiresApiKey;
    }

    public static ApiProvider fromName(String name) {
        for (ApiProvider provider : values()) {
            if (provider.name().equals(name)) {
                return provider;
            }
        }
        return OLLAMA;
    }

    public static String[] getDisplayNames() {
        ApiProvider[] providers = values();
        String[] names = new String[providers.length];
        for (int i = 0; i < providers.length; i++) {
            names[i] = providers[i].displayName;
        }
        return names;
    }
}
