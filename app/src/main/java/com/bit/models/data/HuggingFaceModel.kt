package com.bit.models.data

import kotlinx.serialization.Serializable

@Serializable
data class HuggingFaceModel(
    val id: String,
    val name: String,
    val description: String,
    val fileUri: String,
    val approximateSize: String,
    val modelType: ModelType,
    val isZip: Boolean,
    val chipsetSuffix: String? = null,
    val runOnCpu: Boolean = false,
    val textEmbeddingSize: Int = 768,
    val tags: List<String> = emptyList(),
    val requiresNPU: Boolean = false,
    val repositoryUrl: String = "",
    val minRamGb: Int = 0,
    val sizeBytes: Long = 0L,
    val icon: String? = null,
    val iconUrl: String? = null,
    val projectorUrl: String? = null
)
@Serializable
data class HFModelRepository(
    val id: String,
    val name: String,
    val repoPath: String,
    val modelType: ModelType,
    val isEnabled: Boolean = true,
    val category: ModelCategory = ModelCategory.GENERAL,
    val source: RepositorySource = RepositorySource.HUGGING_FACE,
    val apiBaseUrl: String = "",
    val apiEndpoint: String = "/api/v1/models",
    val apiAuthToken: String = ""
)
