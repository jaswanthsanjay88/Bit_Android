package com.atharva.ollama.api;

import com.google.gson.JsonObject;

import retrofit2.Call;
import retrofit2.http.GET;
import retrofit2.http.Query;

/**
 * Interface for SearXNG API.
 */
public interface SearxApi {
    @GET("search")
    Call<JsonObject> search(
        @Query("q") String query,
        @Query("format") String format // "json"
    );
}
