package com.bit.api.openai

class CustomOpenAiProvider(
    override val name: String,
    override val defaultBaseUrl: String
) : BaseOpenAiProvider() {

    override val retryableStatusCodes: Set<Int> = setOf(401, 429, 502, 503, 504)

    override val retryMissingV1BaseUrl: Boolean = true

    override fun retryDelayMillis(statusCode: Int, attempt: Int): Long =
        if (statusCode == 401) 5000L else super.retryDelayMillis(statusCode, attempt)
}
