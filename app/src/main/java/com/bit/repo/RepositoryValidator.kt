package com.bit.repo

import com.bit.models.data.HFModelRepository
import com.bit.models.data.ModelType
import com.bit.models.data.RepositorySource
import com.bit.network.HuggingFaceClient
import com.bit.network.ExternalModelApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class ValidationResult {
    data class Valid(val ggufFileCount: Int, val label: String = "GGUF") : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult()
    object Checking : ValidationResult()
}

class RepositoryValidator {

    private val api = HuggingFaceClient.api
    private val validationCache = mutableMapOf<String, ValidationResult>()

    /**
     * Validates a HuggingFace repository by checking:
     * 1. Repository exists (getRepoInfo returns success)
     * 2. Repository contains GGUF files (getRepoFiles contains .gguf files)
     */
    suspend fun validateRepository(repo: HFModelRepository): ValidationResult = withContext(Dispatchers.IO) {
        try {
            if (repo.source == RepositorySource.CUSTOM_API) {
                return@withContext validateCustomApiRepository(repo)
            }

            // Check cache first
            validationCache[repo.id]?.let { cachedResult ->
                if (cachedResult !is ValidationResult.Checking) {
                    return@withContext cachedResult
                }
            }

            // Step 1: Check if repository exists
            val repoInfoResponse = api.getRepoInfo(repo.repoPath)

            if (!repoInfoResponse.isSuccessful) {
                val errorMessage = when (repoInfoResponse.code()) {
                    404 -> "Repository not found (404)"
                    401, 403 -> "Access denied (${repoInfoResponse.code()})"
                    else -> "HTTP error (${repoInfoResponse.code()})"
                }
                return@withContext ValidationResult.Invalid(errorMessage).also {
                    validationCache[repo.id] = it
                }
            }

            // Step 2: Check for model files based on repo type
            val filesResponse = api.getRepoFiles(repo.repoPath)

            if (!filesResponse.isSuccessful) {
                return@withContext ValidationResult.Invalid("Failed to fetch files").also {
                    validationCache[repo.id] = it
                }
            }

            val files = filesResponse.body() ?: emptyList()

            val (matchingFiles, fileLabel) = when (repo.modelType) {
                ModelType.SD -> {
                    files.filter { it.path.endsWith(".zip", ignoreCase = true) } to "ZIP"
                }
                ModelType.TTS -> {
                    files.filter { it.path.endsWith(".onnx", ignoreCase = true) } to "ONNX"
                }
                else -> {
                    files.filter { it.path.endsWith(".gguf", ignoreCase = true) } to "GGUF"
                }
            }

            if (matchingFiles.isEmpty()) {
                return@withContext ValidationResult.Invalid("No $fileLabel files found").also {
                    validationCache[repo.id] = it
                }
            }

            // Success
            ValidationResult.Valid(matchingFiles.size, fileLabel).also {
                validationCache[repo.id] = it
            }

        } catch (e: Exception) {
            ValidationResult.Invalid("Error: ${e.message ?: "Unknown error"}").also {
                validationCache[repo.id] = it
            }
        }
    }

    private suspend fun validateCustomApiRepository(repo: HFModelRepository): ValidationResult {
        if (repo.apiBaseUrl.isBlank()) {
            return ValidationResult.Invalid("API base URL is required")
        }

        return try {
            val response = ExternalModelApiClient.fetchCatalog(repo)
            if (!response.isSuccessful) {
                return ValidationResult.Invalid("API error (${response.code()})")
            }

            val body = response.body()
            val count = when {
                body == null || body.isJsonNull -> 0
                body.isJsonArray -> body.asJsonArray.size()
                body.isJsonObject -> {
                    val obj = body.asJsonObject
                    when {
                        obj.has("models") && obj.get("models").isJsonArray -> obj.getAsJsonArray("models").size()
                        obj.has("data") && obj.get("data").isJsonArray -> obj.getAsJsonArray("data").size()
                        else -> 0
                    }
                }
                else -> 0
            }

            if (count <= 0) ValidationResult.Invalid("No models returned by API")
            else ValidationResult.Valid(count, "API")
        } catch (e: Exception) {
            ValidationResult.Invalid("API validation failed: ${e.message ?: "Unknown error"}")
        }
    }

}
