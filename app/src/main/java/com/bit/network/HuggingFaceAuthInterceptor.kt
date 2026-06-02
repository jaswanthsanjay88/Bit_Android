package com.bit.network

import com.bit.data.HuggingFaceTokenManager
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OkHttp Interceptor that automatically injects the HuggingFace Bearer token
 * into all outbound requests when a token is available.
 *
 * Reads the token from [HuggingFaceTokenManager] at request time (not at init),
 * so token changes take effect immediately without restarting the app.
 *
 * Skips injection if the request already has an Authorization header.
 */
@Singleton
class HuggingFaceAuthInterceptor @Inject constructor(
    private val tokenManager: HuggingFaceTokenManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        // Only attach if token exists and no existing auth header
        if (original.header("Authorization") == null) {
            tokenManager.getBearerHeader()?.let { bearer ->
                builder.header("Authorization", bearer)
            }
        }

        return chain.proceed(builder.build())
    }
}
