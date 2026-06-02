package com.bit.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier to distinguish the HuggingFace-specific OkHttpClient
 * from any other OkHttpClient instances in the graph.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class HuggingFaceOkHttp

/**
 * Hilt module providing the HuggingFace network stack.
 *
 * The [HuggingFaceAuthInterceptor] is automatically injected and will
 * attach Bearer tokens to all requests when a token is stored.
 */
@Module
@InstallIn(SingletonComponent::class)
object HuggingFaceNetworkModule {

    @Provides
    @Singleton
    @HuggingFaceOkHttp
    fun provideHuggingFaceOkHttp(
        authInterceptor: HuggingFaceAuthInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideHuggingFaceApi(
        @HuggingFaceOkHttp client: OkHttpClient
    ): HuggingFaceApi = Retrofit.Builder()
        .baseUrl("https://huggingface.co/")
        .client(client)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
        .create(HuggingFaceApi::class.java)
}
