package com.bit.network

import com.google.gson.JsonElement
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

interface ExternalModelApiService {
    @GET
    suspend fun getCatalog(
        @Url url: String,
        @Header("Authorization") authHeader: String? = null
    ): Response<JsonElement>
}
