package com.atharva.ollama.api;

import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * Singleton Retrofit client for Ollama API.
 */
public class RetrofitClient {

    private static Retrofit retrofit = null;
    private static String currentBaseUrl = null;
    private static OkHttpClient httpClient = null;

    /**
     * Get or create a Retrofit instance for the given base URL.
     */
    public static Retrofit getClient(String baseUrl) {
        if (retrofit == null || !baseUrl.equals(currentBaseUrl)) {
            HttpLoggingInterceptor loggingInterceptor = new HttpLoggingInterceptor();
            loggingInterceptor.setLevel(HttpLoggingInterceptor.Level.BASIC);

            httpClient = new OkHttpClient.Builder()
                    .addInterceptor(loggingInterceptor)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)  // Long timeout for streaming
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();

            retrofit = new Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(httpClient)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build();

            currentBaseUrl = baseUrl;
        }
        return retrofit;
    }

    /**
     * Get the OllamaApi interface.
     */
    public static OllamaApi getApi(String baseUrl) {
        return getClient(baseUrl).create(OllamaApi.class);
    }

    /**
     * Get the OkHttpClient for direct streaming calls.
     */
    public static OkHttpClient getHttpClient() {
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(300, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .build();
        }
        return httpClient;
    }

    /**
     * Reset the client.
     */
    public static void reset() {
        retrofit = null;
        currentBaseUrl = null;
        httpClient = null;
    }
}
