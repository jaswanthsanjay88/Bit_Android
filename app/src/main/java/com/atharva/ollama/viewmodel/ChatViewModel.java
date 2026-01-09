package com.atharva.ollama.viewmodel;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.atharva.ollama.api.UnifiedApiClient;
import com.atharva.ollama.database.AppDatabase;
import com.atharva.ollama.repository.ChatRepository;
import com.atharva.ollama.util.PreferencesManager;

public class ChatViewModel extends AndroidViewModel {

    private final ChatRepository repository;
    private final MutableLiveData<String> streamingText = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isStreaming = new MutableLiveData<>(false);
    private final MutableLiveData<String> error = new MutableLiveData<>();
    private final MutableLiveData<String> status = new MutableLiveData<>();
    
    private long lastUiUpdate = 0;
    // Lower interval for smoother 60fps-like streaming animation
    private static final long UI_UPDATE_INTERVAL_MS = 16; 

    private final MutableLiveData<java.util.List<String>> pendingImages = new MutableLiveData<>(new java.util.ArrayList<>());

    public ChatViewModel(@NonNull Application application) {
        super(application);
        AppDatabase db = AppDatabase.getInstance(application);
        UnifiedApiClient apiClient = new UnifiedApiClient(application);
        PreferencesManager prefs = new PreferencesManager(application);
        repository = new ChatRepository(db, apiClient, prefs);
    }

    public LiveData<String> getStreamingText() { return streamingText; }
    public LiveData<Boolean> getIsStreaming() { return isStreaming; }
    public LiveData<String> getError() { return error; }
    public LiveData<String> getStatus() { return status; }
    public LiveData<java.util.List<String>> getPendingImages() { return pendingImages; }

    public void addPendingImage(String imagePath) {
        java.util.List<String> current = pendingImages.getValue();
        if (current != null) {
            current.add(imagePath);
            pendingImages.setValue(current);
        }
    }

    public void removePendingImage(int index) {
        java.util.List<String> current = pendingImages.getValue();
        if (current != null && index >= 0 && index < current.size()) {
            current.remove(index);
            pendingImages.setValue(current);
        }
    }
    
    public void clearPendingImages() {
        pendingImages.setValue(new java.util.ArrayList<>());
    }

    public void sendMessage(long conversationId, String message) {
        isStreaming.setValue(true);
        streamingText.setValue(""); 
        status.setValue(null);
        lastUiUpdate = 0;
        
        // Capture current images and clear pending
        java.util.List<String> imagesToSend = new java.util.ArrayList<>(pendingImages.getValue() != null ? pendingImages.getValue() : new java.util.ArrayList<>());
        clearPendingImages();

        repository.sendMessage(conversationId, message, imagesToSend, new ChatRepository.ChatCallback() {
            @Override
            public void onToken(String token) {
                long now = System.currentTimeMillis();
                if (now - lastUiUpdate >= UI_UPDATE_INTERVAL_MS) {
                    lastUiUpdate = now;
                    streamingText.postValue(token);
                }
            }

            @Override
            public void onComplete(String fullResponse) {
                streamingText.postValue(fullResponse);
                isStreaming.postValue(false);
                status.postValue(null);
            }

            @Override
            public void onError(String err) {
                isStreaming.postValue(false);
                error.postValue(err);
                status.postValue(null);
            }

            @Override
            public void onSearchStatus(String msg) {
                status.postValue(msg);
            }
        });
    }
    
    /**
     * Cancel ongoing generation.
     */
    public void cancelGeneration() {
        repository.cancelCurrentRequest();
        isStreaming.postValue(false);
        status.postValue(null);
    }
}
