package com.bit.update

import com.google.gson.annotations.SerializedName
import retrofit2.http.GET
import retrofit2.http.Path

// ---------------------------------------------------------------------------
// GitHub Releases API models
// ---------------------------------------------------------------------------
data class GithubRelease(
    @SerializedName("tag_name") val tagName: String,
    @SerializedName("name") val name: String?,
    @SerializedName("body") val body: String?,
    @SerializedName("assets") val assets: List<GithubAsset>,
    @SerializedName("html_url") val htmlUrl: String,
    @SerializedName("prerelease") val prerelease: Boolean = false,
    @SerializedName("draft") val draft: Boolean = false
)

data class GithubAsset(
    @SerializedName("name") val name: String,
    @SerializedName("browser_download_url") val browserDownloadUrl: String,
    @SerializedName("size") val size: Long,
    @SerializedName("content_type") val contentType: String
)

interface GithubApi {
    @GET("repos/{owner}/{repo}/releases")
    suspend fun getReleases(
        @Path("owner") owner: String,
        @Path("repo") repo: String
    ): List<GithubRelease>
}

object GithubApiFactory {
    const val OWNER = "jaswanthsanjay88"
    const val REPO = "Bit_Android"

    fun create(): GithubApi {
        val retrofit = retrofit2.Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
        return retrofit.create(GithubApi::class.java)
    }
}
