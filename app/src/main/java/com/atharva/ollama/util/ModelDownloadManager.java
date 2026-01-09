package com.atharva.ollama.util;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manager for downloading GGUF models from HuggingFace.
 * Uses public repos that don't require authentication.
 */
public class ModelDownloadManager {

    private static final String TAG = "ModelDownloadManager";
    private static final String HF_BASE_URL = "https://huggingface.co/";
    private static final int BUFFER_SIZE = 8192;
    
    private final Context context;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private volatile boolean isCancelled = false;

    /**
     * Available GGUF models for download.
     * All models are public and don't require authentication.
     */
    public enum GemmaModel {
        // Small/tiny models - perfect for mobile
        SMOLLM2_360M_Q4("SmolLM2 360M (Q4)", "QuantFactory/SmolLM2-360M-Instruct-GGUF", "SmolLM2-360M-Instruct.Q4_K_M.gguf", 220),
        QWEN2_0_5B_Q4("Qwen2.5 0.5B (Q4)", "Qwen/Qwen2.5-0.5B-Instruct-GGUF", "qwen2.5-0.5b-instruct-q4_k_m.gguf", 400),
        TINYLLAMA_1_1B_Q4("TinyLlama 1.1B (Q4)", "TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF", "tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf", 670),
        PHI3_MINI_Q4("Phi-3.5 Mini (Q4)", "bartowski/Phi-3.5-mini-instruct-GGUF", "Phi-3.5-mini-instruct-Q4_K_M.gguf", 2400);

        public final String displayName;
        public final String repoId;
        public final String fileName;
        public final int sizeMB; // Approximate size in MB

        GemmaModel(String displayName, String repoId, String fileName, int sizeMB) {
            this.displayName = displayName;
            this.repoId = repoId;
            this.fileName = fileName;
            this.sizeMB = sizeMB;
        }

        public String getDownloadUrl() {
            return HF_BASE_URL + repoId + "/resolve/main/" + fileName;
        }

        public static String[] getDisplayNames() {
            GemmaModel[] models = values();
            String[] names = new String[models.length];
            for (int i = 0; i < models.length; i++) {
                names[i] = models[i].displayName + " (~" + models[i].sizeMB + "MB)";
            }
            return names;
        }
    }

    /**
     * Callback for download progress and completion.
     */
    public interface DownloadCallback {
        void onProgress(int percentage, long downloadedBytes, long totalBytes);
        void onComplete(String filePath);
        void onError(String error);
    }

    public ModelDownloadManager(Context context) {
        this.context = context.getApplicationContext();
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Get the directory where models are stored.
     */
    public File getModelsDirectory() {
        File modelsDir = new File(context.getFilesDir(), "models");
        if (!modelsDir.exists()) {
            modelsDir.mkdirs();
        }
        return modelsDir;
    }

    /**
     * Check if a model is already downloaded.
     */
    public boolean isModelDownloaded(GemmaModel model) {
        File modelFile = new File(getModelsDirectory(), model.fileName);
        return modelFile.exists() && modelFile.length() > 0;
    }

    /**
     * Get the file path for a downloaded model.
     */
    public String getModelPath(GemmaModel model) {
        return new File(getModelsDirectory(), model.fileName).getAbsolutePath();
    }

    /**
     * Download a model from HuggingFace.
     * 
     * @param model The model to download
     * @param hfToken HuggingFace access token (optional for public models)
     * @param callback Progress and completion callback
     */
    public void downloadModel(GemmaModel model, String hfToken, DownloadCallback callback) {
        // Token is optional for public GGUF models
        isCancelled = false;
        
        executor.execute(() -> {
            HttpURLConnection connection = null;
            InputStream inputStream = null;
            FileOutputStream outputStream = null;
            
            try {
                File outputFile = new File(getModelsDirectory(), model.fileName);
                File tempFile = new File(getModelsDirectory(), model.fileName + ".tmp");
                
                // Resume support: check if partial download exists
                long existingBytes = tempFile.exists() ? tempFile.length() : 0;
                
                URL url = new URL(model.getDownloadUrl());
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                
                // Only add auth header if token is provided
                if (hfToken != null && !hfToken.trim().isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + hfToken.trim());
                }
                
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(60000);
                
                // Resume from existing bytes if any
                if (existingBytes > 0) {
                    connection.setRequestProperty("Range", "bytes=" + existingBytes + "-");
                }
                
                int responseCode = connection.getResponseCode();
                Log.d(TAG, "Download response code: " + responseCode);
                
                if (responseCode == 401 || responseCode == 403) {
                    mainHandler.post(() -> callback.onError("Authentication failed. Check your HuggingFace token and ensure you've accepted the model's terms."));
                    return;
                }
                
                if (responseCode != 200 && responseCode != 206) {
                    mainHandler.post(() -> callback.onError("Download failed with code: " + responseCode));
                    return;
                }
                
                long totalBytes = connection.getContentLengthLong();
                if (responseCode == 206) {
                    // Partial content - add existing bytes
                    totalBytes += existingBytes;
                }
                
                final long finalTotalBytes = totalBytes;
                
                inputStream = new BufferedInputStream(connection.getInputStream(), BUFFER_SIZE);
                outputStream = new FileOutputStream(tempFile, responseCode == 206);
                
                byte[] buffer = new byte[BUFFER_SIZE];
                long downloadedBytes = existingBytes;
                int bytesRead;
                long lastUpdateTime = 0;
                
                while ((bytesRead = inputStream.read(buffer)) != -1 && !isCancelled) {
                    outputStream.write(buffer, 0, bytesRead);
                    downloadedBytes += bytesRead;
                    
                    // Update progress every 500ms to avoid UI spam
                    long now = System.currentTimeMillis();
                    if (now - lastUpdateTime > 500) {
                        lastUpdateTime = now;
                        final long currentBytes = downloadedBytes;
                        final int percentage = finalTotalBytes > 0 ? (int) ((currentBytes * 100) / finalTotalBytes) : 0;
                        mainHandler.post(() -> callback.onProgress(percentage, currentBytes, finalTotalBytes));
                    }
                }
                
                outputStream.flush();
                outputStream.close();
                outputStream = null;
                
                if (isCancelled) {
                    mainHandler.post(() -> callback.onError("Download cancelled"));
                    return;
                }
                
                // Rename temp file to final file
                if (tempFile.renameTo(outputFile)) {
                    final String filePath = outputFile.getAbsolutePath();
                    Log.d(TAG, "Model downloaded successfully: " + filePath);
                    mainHandler.post(() -> callback.onComplete(filePath));
                } else {
                    mainHandler.post(() -> callback.onError("Failed to save model file"));
                }
                
            } catch (IOException e) {
                Log.e(TAG, "Download error", e);
                final String errorMsg = e.getMessage();
                mainHandler.post(() -> callback.onError("Download failed: " + errorMsg));
            } finally {
                try {
                    if (inputStream != null) inputStream.close();
                    if (outputStream != null) outputStream.close();
                    if (connection != null) connection.disconnect();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing streams", e);
                }
            }
        });
    }

    /**
     * Cancel ongoing download.
     */
    public void cancelDownload() {
        isCancelled = true;
    }

    /**
     * Delete a downloaded model.
     */
    public boolean deleteModel(GemmaModel model) {
        File modelFile = new File(getModelsDirectory(), model.fileName);
        File tempFile = new File(getModelsDirectory(), model.fileName + ".tmp");
        
        boolean deleted = false;
        if (modelFile.exists()) {
            deleted = modelFile.delete();
        }
        if (tempFile.exists()) {
            tempFile.delete();
        }
        return deleted;
    }
}
