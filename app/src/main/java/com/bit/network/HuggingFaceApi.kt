package com.bit.network

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface HuggingFaceApi {
    
    @GET("api/models/{repo}")
    suspend fun getRepoInfo(@Path("repo", encoded = true) repo: String): Response<HuggingFaceRepoResponse>
    
    @GET("api/models/{repo}/tree/main")
    suspend fun getRepoFiles(
        @Path("repo", encoded = true) repo: String,
        @Query("recursive") recursive: Boolean = true
    ): Response<List<HuggingFaceFileResponse>>

    @GET("api/models")
    suspend fun searchModels(
        @Query("filter") filter: String? = null,
        @Query("search") search: String? = null,
        @Query("sort") sort: String = "downloads",
        @Query("direction") direction: Int = -1,
        @Query("limit") limit: Int = 20
    ): Response<List<HuggingFaceSearchRepoResponse>>

    /** Validates the current access token. Returns user profile on success, 401 on failure. */
    @GET("api/whoami-v2")
    suspend fun whoami(): Response<WhoAmIResponse>
}

data class HuggingFaceRepoResponse(
    val modelId: String?,
    val id: String?,
    val siblings: List<HuggingFaceFileResponse>?
)

data class HuggingFaceFileResponse(
    @SerializedName("path") val rawPath: String? = null,
    @SerializedName("rfilename") val rfilename: String? = null,
    val size: Long? = null
) {
    val path: String
        get() = rfilename?.takeIf { it.isNotBlank() } ?: rawPath ?: ""
}

data class HuggingFaceSearchRepoResponse(
    val id: String,
    val author: String?,
    val downloads: Long?,
    val likes: Long?,
    val tags: List<String>?,
    val gated: Boolean?
)

/** Response from /api/whoami-v2 — used to validate access tokens. */
data class WhoAmIResponse(
    val name: String?,
    val fullname: String?,
    val email: String?,
    val type: String?  // "user" or "org"
)

