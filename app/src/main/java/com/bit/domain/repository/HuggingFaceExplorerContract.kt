package com.bit.domain.repository

import com.bit.repo.HuggingFaceExplorerRepo

/**
 * Contract for HuggingFace model exploration.
 * Enables testability and decouples ViewModels from network implementation.
 */
interface HuggingFaceExplorerContract {
    suspend fun searchGgufRepositories(query: String, limit: Int = 20): Result<List<HuggingFaceExplorerRepo>>
}
