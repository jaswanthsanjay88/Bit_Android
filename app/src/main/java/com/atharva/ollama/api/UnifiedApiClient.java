package com.atharva.ollama.api;

import android.util.Log;

import androidx.annotation.NonNull;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.atharva.ollama.util.Prompts;
import com.atharva.ollama.util.WebSearchHelper;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.io.BufferedReader;
import java.io.IOException;

/**
 * Unified API Client that handles different providers, streaming, and RAG.
 */
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Unified API Client that handles different providers, streaming, and RAG.
 */
public class UnifiedApiClient {

    private static final String TAG = "UnifiedApiClient";
    private android.content.Context appContext;

    public interface StreamCallback {
        void onToken(String token);
        void onComplete(String fullResponse);
        void onError(String error);
        
        /**
         * Called when status changes during generation.
         * @param status Status message like "Searching web...", "Thinking...", "Generating..."
         */
        default void onStatus(String status) {
            // Default empty implementation
        }

        /**
         * Called when search results are available.
         */
        default void onSources(java.util.List<com.atharva.ollama.model.ChatMessage.Source> sources) {
            // Default empty implementation
        }
    }

    public static class ChatMessageData {
        public String role;
        public String content;
        public java.util.List<String> images;

        public ChatMessageData(String role, String content) {
            this.role = role;
            this.content = content;
        }

        public ChatMessageData(String role, String content, java.util.List<String> images) {
            this.role = role;
            this.content = content;
            this.images = images;
        }
    }

    private final OkHttpClient sharedClient;
    private volatile Call<?> activeCall;
    private volatile boolean isCancelled = false;
    private AiCoreClient aiCoreClient;

    public UnifiedApiClient() {
        this.sharedClient = new OkHttpClient.Builder()
                .readTimeout(120, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    
    public UnifiedApiClient(android.content.Context context) {
        this();
        this.appContext = context.getApplicationContext();
    }

    /**
     * Cancel any active streaming request.
     */
    public void cancelRequest() {
        isCancelled = true;
        if (activeCall != null) {
            activeCall.cancel();
            activeCall = null;
        }
    }

    /**
     * Check if the current request was cancelled.
     */
    public boolean isCancelled() {
        return isCancelled;
    }

    /**
     * Reset cancellation state before new request.
     */
    private void resetCancellation() {
        isCancelled = false;
        activeCall = null;
    }

    /**
     * Main entry point for streaming chat.
     * Supports RAG if searchEnabled is true.
     */
    
    // Status constants for typing indicator
    public static final String STATUS_THINKING = "Thinking…";
    public static final String STATUS_SEARCHING = "Searching web…";
    public static final String STATUS_GENERATING = "Generating…";
    public static final String STATUS_REPHRASING = "Understanding query…";
    
    public void streamChat(
            ApiProvider provider,
            String baseUrl,
            String apiKey,
            String model,
            String systemPrompt,
            List<ChatMessageData> history,
            boolean searchEnabled,
            String searxHost,
            String searchSiteFilter,
            StreamCallback callback
    ) {
        // Reset cancellation state for new request
        resetCancellation();
        
        // If RAG is enabled, we first perform the search, then update the history/context
        if (searchEnabled) {
            // Get the last user message for the query
            String query = "";
            if (!history.isEmpty()) {
                ChatMessageData lastMsg = history.get(history.size() - 1);
                if ("user".equals(lastMsg.role)) {
                    query = lastMsg.content;
                }
            }

            if (!query.isEmpty()) {
                Log.d(TAG, "Starting smart web search flow for: " + query);
                
                // Emit status: Understanding query
                callback.onStatus(STATUS_REPHRASING);
                
                // Step 1: Rephrase the query
                StringBuilder chatHistoryStr = new StringBuilder();
                for (int i = 0; i < history.size() - 1; i++) { // Exclude last message which is the query
                    ChatMessageData msg = history.get(i);
                    chatHistoryStr.append(msg.role).append(": ").append(msg.content).append("\n");
                }

                String rephraserPrompt = Prompts.WEB_SEARCH_RETRIEVER_PROMPT
                        .replace("{chat_history}", chatHistoryStr.toString())
                        .replace("{query}", query);

                // Use a temporary history for the rephraser
                List<ChatMessageData> rephraserHistory = new java.util.ArrayList<>();
                rephraserHistory.add(new ChatMessageData("user", rephraserPrompt));

                // Call the API to rephrase (using the same provider/model for simplicity)
                // We use a separate callback to capture the result
                proceedWithChat(provider, baseUrl, apiKey, model, "", rephraserHistory, new StreamCallback() {
                    StringBuilder accumulator = new StringBuilder();

                    @Override
                    public void onToken(String token) {
                        accumulator.append(token);
                    }

                    @Override
                    public void onComplete(String fullResponse) {
                        Log.d(TAG, "Rephraser response: " + fullResponse);
                        handleRephraserResponse(fullResponse, provider, baseUrl, apiKey, model, systemPrompt, history, searxHost, searchSiteFilter, callback);
                    }

                    @Override
                    public void onError(String error) {
                        Log.e(TAG, "Rephraser failed: " + error);
                        // Fallback to normal chat without search
                        proceedWithChat(provider, baseUrl, apiKey, model, systemPrompt, history, callback);
                    }
                });
                return;
            }
        }

        // Standard flow without search - emit thinking status
        callback.onStatus(STATUS_THINKING);
        proceedWithChat(provider, baseUrl, apiKey, model, systemPrompt, history, callback);
    }

    private void handleRephraserResponse(
            String response,
            ApiProvider provider,
            String baseUrl,
            String apiKey,
            String model,
            String systemPrompt,
            List<ChatMessageData> history,
            String searxHost,
            String searchSiteFilter,
            StreamCallback callback
    ) {
        String question = extractXmlTag(response, "question");
        String links = extractXmlTag(response, "links");

        Log.d(TAG, "Parsed Question: " + question);
        Log.d(TAG, "Parsed Links: " + links);

        if (question == null || "not_needed".equalsIgnoreCase(question.trim())) {
            Log.d(TAG, "Search not needed. Proceeding with normal chat.");
            callback.onStatus(STATUS_THINKING);
            proceedWithChat(provider, baseUrl, apiKey, model, systemPrompt, history, callback);
            return;
        }

        // TODO: Handle "summarize" and "links" logic if needed. For now, we treat "question" as the search query.
        // If question is "summarize", we might need to fetch the link content. 
        // For this implementation, we will search for the question if it's not "summarize", 
        // or if it is "summarize", we'll try to search for the link (basic fallback).
        
        String searchQuery = question;
        if ("summarize".equalsIgnoreCase(question.trim()) && links != null) {
            searchQuery = links; // Basic fallback: search for the URL to get snippets
        }
        
        // Emit status: Searching web
        callback.onStatus(STATUS_SEARCHING);

        performSearch(searxHost, searchQuery, searchSiteFilter, new SearchCallback() {
            @Override
            public void onResults(String searchContext, java.util.List<com.atharva.ollama.model.ChatMessage.Source> sources) {
                // Emit status: Thinking
                callback.onStatus(STATUS_THINKING);
                
                // Emit sources to UI
                if (sources != null) {
                    callback.onSources(sources);
                }
                
                String date = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(new java.util.Date());
                
                String responsePrompt = Prompts.WEB_SEARCH_RESPONSE_PROMPT
                        .replace("{systemInstructions}", systemPrompt)
                        .replace("{context}", searchContext)
                        .replace("{date}", date);

                // We replace the system prompt with our new RAG prompt
                // And we keep the history as is (the model will see the user's original question)
                proceedWithChat(provider, baseUrl, apiKey, model, responsePrompt, history, callback);
            }

            @Override
            public void onUnsuccessful(String reason) {
                Log.e(TAG, "Search failed: " + reason);
                proceedWithChat(provider, baseUrl, apiKey, model, systemPrompt, history, callback);
            }
        });
    }

    /**
     * Extract the LAST occurrence of an XML tag from text, excluding any that appear within examples.
     * This is important because LLM responses may echo back examples before the actual answer.
     */
    private String extractXmlTag(String text, String tag) {
        // First, try to find content after </examples> if it exists
        String textToSearch = text;
        int examplesEnd = text.lastIndexOf("</examples>");
        if (examplesEnd != -1) {
            textToSearch = text.substring(examplesEnd);
        }
        
        Pattern pattern = Pattern.compile("<" + tag + ">(.*?)</" + tag + ">", Pattern.DOTALL);
        Matcher matcher = pattern.matcher(textToSearch);
        String lastMatch = null;
        while (matcher.find()) {
            lastMatch = matcher.group(1).trim();
        }
        
        // Fallback to searching entire text if nothing found after examples
        if (lastMatch == null) {
            matcher = pattern.matcher(text);
            while (matcher.find()) {
                lastMatch = matcher.group(1).trim();
            }
        }
        
        return lastMatch;
    }

    private void proceedWithChat(
            ApiProvider provider,
            String baseUrl,
            String apiKey,
            String model,
            String systemPrompt,
            List<ChatMessageData> history,
            StreamCallback callback) {

        Log.d(TAG, "Proceeding with chat. Provider: " + provider.name());

        try {
            switch (provider) {
                case OLLAMA:
                    streamOllama(baseUrl, model, systemPrompt, history, callback);
                    break;
                case MEDIAPIPE:
                    streamGeminiNano(systemPrompt, history, callback);
                    break;
                case ANTHROPIC:
                    streamAnthropic(baseUrl, apiKey, model, systemPrompt, history, callback);
                    break;
                case OPENAI:
                case OPENROUTER:
                case GROQ:
                case TOGETHER:
                case CUSTOM:
                default:
                    streamOpenAICompatible(baseUrl, apiKey, model, systemPrompt, history, callback);
                    break;
            }
        } catch (Exception e) {
            callback.onError(e.getMessage());
        }
    }

    // --- Search Logic ---

    interface SearchCallback {
        void onResults(String context, java.util.List<com.atharva.ollama.model.ChatMessage.Source> sources);
        void onUnsuccessful(String reason);
    }

    private void performSearch(String host, String query, String siteFilter, SearchCallback callback) {
        Log.d(TAG, "Performing DuckDuckGo search for query: " + query);
        if (siteFilter != null && !siteFilter.isEmpty()) {
            Log.d(TAG, "With site filter: " + siteFilter);
        }
        
        // Use DuckDuckGo HTML scraping as primary search method
        // This is more reliable than API-based search which often gets rate-limited
        WebSearchHelper.searchAsync(query, 5, siteFilter != null ? siteFilter : "", new WebSearchHelper.SearchCallback() {
            @Override
            public void onSuccess(String formattedResults, java.util.List<WebSearchHelper.SearchResult> results) {
                if (results != null && !results.isEmpty()) {
                    Log.d(TAG, "DuckDuckGo search successful. Found " + results.size() + " results.");
                    
                    // Convert to Source objects
                    java.util.List<com.atharva.ollama.model.ChatMessage.Source> sources = new java.util.ArrayList<>();
                    for (WebSearchHelper.SearchResult res : results) {
                        sources.add(new com.atharva.ollama.model.ChatMessage.Source(res.title, res.url, res.snippet));
                    }
                    callback.onResults(formattedResults, sources);
                } else {
                    Log.w(TAG, "DuckDuckGo returned no results, trying SearXNG fallback...");
                    // Fallback to SearXNG if configured
                    if (host != null && !host.isEmpty()) {
                        performSearxSearch(host, query, callback);
                    } else {
                        callback.onUnsuccessful("No search results found");
                    }
                }
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "DuckDuckGo search failed: " + error + ", trying SearXNG fallback...");
                // Fallback to SearXNG if configured
                if (host != null && !host.isEmpty()) {
                    performSearxSearch(host, query, callback);
                } else {
                    callback.onUnsuccessful("Search failed: " + error);
                }
            }
        });
    }
    
    /**
     * Fallback search using SearXNG API
     */
    private void performSearxSearch(String host, String query, SearchCallback callback) {
        if (!host.startsWith("http")) {
            host = "https://" + host;
        }
        if (!host.endsWith("/")) host += "/";
        
        Log.d(TAG, "Performing SearXNG search on: " + host + " for query: " + query);
        
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(host)
                .client(sharedClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        SearxApi api = retrofit.create(SearxApi.class);
        api.search(query, "json").enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonObject body = response.body();
                    StringBuilder context = new StringBuilder();
                    
                    if (body.has("results")) {
                        JsonArray results = body.getAsJsonArray("results");
                        int limit = Math.min(results.size(), 5);
                        
                        java.util.List<com.atharva.ollama.model.ChatMessage.Source> sources = new java.util.ArrayList<>();
                        
                        for (int i = 0; i < limit; i++) {
                            JsonObject result = results.get(i).getAsJsonObject();
                            String title = result.has("title") ? result.get("title").getAsString() : "No Title";
                            String content = result.has("content") ? result.get("content").getAsString() : "";
                            String url = result.has("url") ? result.get("url").getAsString() : "";
                            
                            sources.add(new com.atharva.ollama.model.ChatMessage.Source(title, url, content));
                            context.append(String.format("[%d] %s\nURL: %s\nContent: %s\n\n", i + 1, title, url, content));
                        }
                        
                        // Pass results
                         if (context.length() > 0) {
                            Log.d(TAG, "SearXNG search successful.");
                            callback.onResults(context.toString(), sources);
                        } else {
                            callback.onUnsuccessful("No results found");
                        }
                    } else {
                         callback.onUnsuccessful("No results found");
                    }
                } else {
                    Log.e(TAG, "SearXNG API error: " + response.code());
                    callback.onUnsuccessful("SearXNG API error: " + response.code());
                }
            }

            @Override
            public void onFailure(Call<JsonObject> call, Throwable t) {
                Log.e(TAG, "SearXNG network error: " + t.getMessage());
                callback.onUnsuccessful("Network error: " + t.getMessage());
            }
        });
    }


    // --- Streaming Logic for Providers ---

    /**
     * Stream chat using MediaPipe on-device LLM inference.
     * Requires Context to be set via constructor.
     */
    /**
     * Stream chat using Ai-Core on-device LLM inference (GGUF).
     * Requires Context to be set via constructor.
     */
    private void streamGeminiNano(
            String systemPrompt,
            List<ChatMessageData> history,
            StreamCallback callback
    ) {
        if (appContext == null) {
            callback.onError("App context not available for on-device AI");
            return;
        }
        
        // Check if model path is set
        PreferencesManager prefs = new PreferencesManager(appContext);
        String modelPath = prefs.getMediaPipeModelPath();
        
        if (modelPath == null || modelPath.isEmpty()) {
            callback.onError("No GGUF model set. Please download a model in Settings.");
            return;
        }

        // Initialize client if needed
        if (aiCoreClient == null) {
            aiCoreClient = new AiCoreClient(appContext);
        }

        // If initialized, generate immediately
        if (aiCoreClient.isInitialized()) {
             doGeneration(systemPrompt, history, callback);
        } else {
            // Else initialize first
            aiCoreClient.initialize(modelPath, (success, message) -> {
                if (success) {
                    doGeneration(systemPrompt, history, callback);
                } else {
                    callback.onError("Model init failed: " + message);
                }
            });
        }
    }

    private void doGeneration(String systemPrompt, List<ChatMessageData> history, StreamCallback callback) {
         aiCoreClient.generateResponse(systemPrompt, history, 1024, new AiCoreClient.GenerationCallback() {
            @Override
            public void onPartialResult(String token) {
                if (!isCancelled) {
                    callback.onToken(token);
                }
            }

            @Override
            public void onComplete(String fullResponse) {
                if (!isCancelled) {
                    callback.onComplete(fullResponse);
                }
            }

            @Override
            public void onError(String error) {
                if (!isCancelled) {
                    callback.onError(error);
                }
            }
        });
    }

    private void streamOllama(
            String baseUrl,
            String model,
            String systemPrompt,
            List<ChatMessageData> history,
            StreamCallback callback
    ) {
        // Use shared client builder to share connection pool and configuration
        OkHttpClient.Builder clientBuilder = sharedClient.newBuilder();
        
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(clientBuilder.build())
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        OllamaApi api = retrofit.create(OllamaApi.class);

        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.addProperty("stream", true);

        JsonArray messages = new JsonArray();
        
        // System prompt
        if (!systemPrompt.isEmpty()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt);
            messages.add(sysMsg);
        }

        // History
        for (ChatMessageData msg : history) {
            JsonObject m = new JsonObject();
            m.addProperty("role", msg.role);
            m.addProperty("content", msg.content);
            // Handle images if present
            if (msg.images != null && !msg.images.isEmpty()) {
                JsonArray images = new JsonArray();
                for (String img : msg.images) {
                    images.add(img);
                }
                m.add("images", images);
            }
            messages.add(m);
        }
        request.add("messages", messages);

        Call<ResponseBody> call = api.streamingChat(request);
        activeCall = call;
        
        call.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (isCancelled) return;
                handleStreamingResponse(response, callback, "message", "content");
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                if (isCancelled || call.isCanceled()) {
                    callback.onComplete(""); // Silent completion on cancel
                    return;
                }
                callback.onError(t.getMessage());
            }
        });
    }

    private void streamOpenAICompatible(
            String baseUrl,
            String apiKey,
            String model,
            String systemPrompt,
            List<ChatMessageData> history,
            StreamCallback callback
    ) {
        // Use shared client builder to share connection pool
        OkHttpClient.Builder clientBuilder = sharedClient.newBuilder();

        if (apiKey != null && !apiKey.isEmpty()) {
            clientBuilder.addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .build()));
        }

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(clientBuilder.build())
                .addConverterFactory(GsonConverterFactory.create())
                .build();

        // Use a generic OpenAI interface
        OpenAIApi api = retrofit.create(OpenAIApi.class);

        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.addProperty("stream", true);

        JsonArray messages = new JsonArray();
        
        if (!systemPrompt.isEmpty()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt);
            messages.add(sysMsg);
        }

        for (ChatMessageData msg : history) {
            JsonObject m = new JsonObject();
            m.addProperty("role", msg.role);
            m.addProperty("content", msg.content);
            messages.add(m);
        }
        request.add("messages", messages);

        Call<ResponseBody> call = api.chatCompletions(request);
        activeCall = call;
        
        call.enqueue(new Callback<ResponseBody>() {
            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (isCancelled) return;
                
                // OpenAI stream format: data: {...}
                if (!response.isSuccessful()) {
                    try {
                        callback.onError(response.errorBody().string());
                    } catch (IOException e) {
                        callback.onError("Unknown error: " + response.code());
                    }
                    return;
                }
                
                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()))) {
                        StringBuilder fullResponse = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null && !isCancelled) {
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if (data.equals("[DONE]")) break;

                                try {
                                    JsonObject json = new JsonParser().parse(data).getAsJsonObject();
                                    JsonArray choices = json.getAsJsonArray("choices");
                                    if (choices.size() > 0) {
                                        JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                                        if (delta.has("content")) {
                                            String token = delta.get("content").getAsString();
                                            fullResponse.append(token);
                                            callback.onToken(fullResponse.toString()); 
                                        }
                                    }
                                } catch (Exception e) {
                                    // Ignore parse errors for incomplete chunks
                                }
                            }
                        }
                        
                        if (!isCancelled) {
                            callback.onComplete(fullResponse.toString());
                        }

                    } catch (IOException e) {
                        if (!isCancelled) {
                            callback.onError(e.getMessage());
                        }
                    }
                }).start();
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                if (isCancelled || call.isCanceled()) {
                    return;
                }
                callback.onError(t.getMessage());
            }
        });
    }

    private void streamAnthropic(
            String baseUrl,
            String apiKey,
            String model,
            String systemPrompt,
            List<ChatMessageData> history,
            StreamCallback callback
    ) {
         OkHttpClient.Builder clientBuilder = sharedClient.newBuilder();

        if (apiKey != null && !apiKey.isEmpty()) {
            clientBuilder.addInterceptor(chain -> chain.proceed(chain.request().newBuilder()
                    .addHeader("x-api-key", apiKey)
                    .addHeader("anthropic-version", "2023-06-01")
                    .build()));
        }

        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(clientBuilder.build())
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        
        AnthropicApi api = retrofit.create(AnthropicApi.class);
        
        JsonObject request = new JsonObject();
        request.addProperty("model", model);
        request.addProperty("stream", true);
        if(!systemPrompt.isEmpty()) {
            request.addProperty("system", systemPrompt);
        }
        
        JsonArray messages = new JsonArray();
        for (ChatMessageData msg : history) {
            JsonObject m = new JsonObject();
            m.addProperty("role", msg.role);
            m.addProperty("content", msg.content);
            messages.add(m);
        }
        request.add("messages", messages);
        
        api.messages(request).enqueue(new Callback<ResponseBody>() {
             @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {
                if (!response.isSuccessful()) {
                    try {
                        callback.onError(response.errorBody().string());
                    } catch (IOException e) {
                        callback.onError("Error: " + response.code());
                    }
                    return;
                }

                new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()))) {
                        StringBuilder fullResponse = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.startsWith("data: ")) {
                                String data = line.substring(6).trim();
                                if(data.equals("[DONE]")) break; // Not standard anthropic but robust check

                                try {
                                    JsonObject json = new JsonParser().parse(data).getAsJsonObject();
                                    String type = json.has("type") ? json.get("type").getAsString() : "";
                                    
                                    if ("content_block_delta".equals(type)) {
                                        JsonObject delta = json.getAsJsonObject("delta");
                                        if(delta.has("text")) {
                                            String text = delta.get("text").getAsString();
                                            fullResponse.append(text);
                                            callback.onToken(fullResponse.toString());
                                        }
                                    }
                                } catch (Exception e) {}
                            }
                        }
                        callback.onComplete(fullResponse.toString());
                    } catch (IOException e) {
                        callback.onError(e.getMessage());
                    }
                }).start();
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                callback.onError(t.getMessage());
            }
        });
    }


    private void handleStreamingResponse(Response<ResponseBody> response, StreamCallback callback, String... jsonPath) {
        if (!response.isSuccessful()) {
            try {
                String err = response.errorBody() != null ? response.errorBody().string() : "Error: " + response.code();
                callback.onError(err);
            } catch (IOException e) {
                callback.onError("Error: " + response.code());
            }
            return;
        }

        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body().byteStream()))) {
                StringBuilder fullResponse = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (isCancelled) break;
                    try {
                        JsonObject json = new JsonParser().parse(line).getAsJsonObject();
                        if (json.has("done") && json.get("done").getAsBoolean()) {
                            break;
                        }
                        
                        // Navigate path: message -> content
                        JsonElement current = json;
                        for (String key : jsonPath) {
                            if (current != null && current.isJsonObject() && current.getAsJsonObject().has(key)) {
                                current = current.getAsJsonObject().get(key);
                            } else {
                                current = null;
                                break;
                            }
                        }
                        
                        if (current != null && current.isJsonPrimitive()) {
                            String token = current.getAsString();
                            fullResponse.append(token);
                            callback.onToken(fullResponse.toString());
                        }
                    } catch (Exception e) {
                        // Ignore parse errors for individual lines
                    }
                }
                
                if (!isCancelled) {
                    callback.onComplete(fullResponse.toString());
                }

            } catch (Exception e) {
                if (!isCancelled) {
                    callback.onError(e.getMessage());
                }
            }
        }).start();
    }
    
    // Auxiliary interfaces for internal use
    interface OpenAIApi {
        @retrofit2.http.POST("chat/completions")
        Call<ResponseBody> chatCompletions(@retrofit2.http.Body JsonObject body);
    }
    interface AnthropicApi {
         @retrofit2.http.POST("messages")
         Call<ResponseBody> messages(@retrofit2.http.Body JsonObject body);
    }
    
    // --- Ai-Core On-Device LLM (llama.cpp) ---
    

}
