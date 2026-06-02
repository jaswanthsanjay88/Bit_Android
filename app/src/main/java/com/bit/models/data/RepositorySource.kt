package com.bit.models.data

import kotlinx.serialization.Serializable

@Serializable
enum class RepositorySource {
    HUGGING_FACE,
    CUSTOM_API
}
