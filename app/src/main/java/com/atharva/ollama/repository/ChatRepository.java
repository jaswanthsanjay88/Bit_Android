package com.atharva.ollama.repository;

import android.util.Log;

import com.atharva.ollama.api.ApiProvider;
import com.atharva.ollama.api.UnifiedApiClient;
import com.atharva.ollama.database.AppDatabase;
import com.atharva.ollama.database.ConversationDao;
import com.atharva.ollama.database.Message;
import com.atharva.ollama.database.MessageDao;
import com.atharva.ollama.util.PreferencesManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatRepository {
    private static final String TAG = "ChatRepository";
    private final MessageDao messageDao;
    private final ConversationDao conversationDao;
    private final UnifiedApiClient apiClient;
    private final PreferencesManager prefs;
    private final ExecutorService executor;

    public ChatRepository(AppDatabase database, UnifiedApiClient apiClient, PreferencesManager prefs) {
        this.messageDao = database.messageDao();
        this.conversationDao = database.conversationDao();
        this.apiClient = apiClient;
        this.prefs = prefs;
        this.executor = Executors.newSingleThreadExecutor();
    }

    public interface ChatCallback {
        void onToken(String token);
        void onComplete(String fullResponse);
        void onError(String error);
        void onSearchStatus(String status);
    }

    public void sendMessage(long conversationId, String userMessage, List<String> images, ChatCallback callback) {
        executor.execute(() -> {
            try {
                // 1. Save User Message
                Message msg = new Message(conversationId, "user", userMessage);
                if (images != null && !images.isEmpty()) {
                    com.google.gson.Gson gson = new com.google.gson.Gson();
                    msg.imagePaths = gson.toJson(images);
                }
                messageDao.insert(msg);

                // Update conversation title if needed
                String firstMsg = messageDao.getFirstUserMessage(conversationId);
                if (firstMsg != null && firstMsg.equals(userMessage)) {
                    String title = userMessage.length() > 30 ? userMessage.substring(0, 30) + "..." : userMessage;
                    conversationDao.updateTitle(conversationId, title);
                }
                conversationDao.updateTimestamp(conversationId, System.currentTimeMillis());

                // 2. Prepare Context
                // Assuming getRecentMessages returns newest first. We need oldest first for API.
                List<Message> history = messageDao.getRecentMessages(conversationId, 20);
                List<UnifiedApiClient.ChatMessageData> chatHistory = new ArrayList<>();
                
                // Iterate backwards to get chronological order
                for (int i = history.size() - 1; i >= 0; i--) {
                    Message m = history.get(i);
                    // Load images if present
                    List<String> msgImages = null;
                    if (m.imagePaths != null && !m.imagePaths.isEmpty()) {
                        // Simple Gson parsing or manual split depending on storage
                        // Assuming simple JSON array for now
                        try {
                            com.google.gson.JsonArray arr = new com.google.gson.JsonParser().parse(m.imagePaths).getAsJsonArray();
                            msgImages = new ArrayList<>();
                            for (com.google.gson.JsonElement el : arr) {
                                msgImages.add(el.getAsString());
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error parsing images", e);
                        }
                    }
                    chatHistory.add(new UnifiedApiClient.ChatMessageData(m.role, m.content, msgImages));
                }

                // 3. Get Settings
                ApiProvider provider = prefs.getApiProvider();
                String baseUrl = prefs.getBaseUrl();
                String apiKey = prefs.getApiKey();
                // For MediaPipe, use model path instead of model name
                String model = (provider == ApiProvider.MEDIAPIPE) 
                        ? prefs.getMediaPipeModelPath() 
                        : prefs.getModel();
                String systemPrompt = prefs.getSystemPrompt();
                boolean webSearch = prefs.isWebSearchEnabled();
                String searxHost = prefs.getSearxHost();
                String searchSiteFilter = prefs.getSearchSiteFilter();

                if (webSearch) {
                    callback.onSearchStatus("Searching web...");
                }

                // 4. Call API
                apiClient.streamChat(provider, baseUrl, apiKey, model, systemPrompt, chatHistory, webSearch, searxHost, searchSiteFilter, new UnifiedApiClient.StreamCallback() {
                    private boolean firstToken = true;
                    private String sourcesJson = null;
                    
                    @Override
                    public void onToken(String token) {
                        // Switch to "Generating" status on first token
                        if (firstToken) {
                            firstToken = false;
                            callback.onSearchStatus(UnifiedApiClient.STATUS_GENERATING);
                        }
                        callback.onToken(token);
                    }

                    @Override
                    public void onComplete(String fullResponse) {
                        // 5. Save Assistant Message (only if not cancelled/empty)
                        if (fullResponse != null && !fullResponse.isEmpty()) {
                            executor.execute(() -> {
                                Message assistantMsg = new Message(conversationId, "assistant", fullResponse);
                                if (sourcesJson != null) {
                                    assistantMsg.sourcesJson = sourcesJson;
                                }
                                messageDao.insert(assistantMsg);
                                conversationDao.updateTimestamp(conversationId, System.currentTimeMillis());
                            });
                        }
                        callback.onComplete(fullResponse);
                    }

                    @Override
                    public void onError(String error) {
                        callback.onError(error);
                    }
                    
                    @Override
                    public void onStatus(String status) {
                        callback.onSearchStatus(status);
                    }
                    
                    @Override
                    public void onSources(java.util.List<com.atharva.ollama.model.ChatMessage.Source> sources) {
                        if (sources != null && !sources.isEmpty()) {
                            try {
                                com.google.gson.Gson gson = new com.google.gson.Gson();
                                sourcesJson = gson.toJson(sources);
                            } catch (Exception e) {
                                Log.e(TAG, "Error serializing sources", e);
                            }
                        }
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Error sending message", e);
                callback.onError("Internal error: " + e.getMessage());
            }
        });
    }
    
    /**
     * Cancel the current streaming request.
     */
    public void cancelCurrentRequest() {
        apiClient.cancelRequest();
    }
}
