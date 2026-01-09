package com.atharva.ollama.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Streaming;

/**
 * Retrofit interface for Ollama API endpoints.
 */
public interface OllamaApi {

    /**
     * Generate a response from the Ollama model (non-streaming).
     */
    @POST("/api/generate")
    Call<JsonObject> generateResponse(@Body JsonObject request);

    /**
     * Generate a streaming response from the Ollama model.
     */
    @Streaming
    @POST("/api/generate")
    Call<ResponseBody> generateStreamingResponse(@Body JsonObject request);

    /**
     * Chat with context (multi-turn conversation).
     */
    @POST("/api/chat")
    Call<JsonObject> chat(@Body JsonObject request);

    /**
     * Streaming chat with context.
     */
    @Streaming
    @POST("/api/chat")
    Call<ResponseBody> streamingChat(@Body JsonObject request);

    /**
     * Get list of available models.
     */
    @GET("/api/tags")
    Call<JsonObject> getModels();

    /**
     * Check if server is running.
     */
    @GET("/")
    Call<ResponseBody> ping();
}
