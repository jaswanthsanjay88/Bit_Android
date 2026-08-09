package com.bit.repo
import com.bit.domain.repository.HuggingFaceExplorerContract
import com.bit.network.HuggingFaceApi
import com.bit.network.HuggingFaceFileResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class HuggingFaceExplorerRepo(
    val id: String,
    val author: String,
    val downloads: Long,
    val likes: Long,
    val gated: Boolean,
    val tags: List<String>
)

@Singleton
class HuggingFaceExplorerRepository @Inject constructor(
    private val api: HuggingFaceApi
) : HuggingFaceExplorerContract {

    override suspend fun searchGgufRepositories(query: String, limit: Int): Result<List<HuggingFaceExplorerRepo>> = withContext(Dispatchers.IO) {
        try {
            val q = query.trim()
            if (q.isBlank()) {
                return@withContext fetchTrendingGgufModels(limit)
            }

            // Guarantee GGUF repositories are returned by explicitly setting filter = "gguf"
            // and trying query with "gguf" tag if standard query yields non-GGUF base repos
            val searchQuery = if (q.contains("gguf", ignoreCase = true)) q else "$q gguf"

            var response = api.searchModels(
                filter = "gguf",
                search = searchQuery,
                sort = "downloads",
                direction = -1,
                limit = limit.coerceIn(1, 50)
            )

            // Fallback try raw query with filter = "gguf" if formatted query yields empty results
            if (response.isSuccessful && response.body().isNullOrEmpty()) {
                response = api.searchModels(
                    filter = "gguf",
                    search = q,
                    sort = "downloads",
                    direction = -1,
                    limit = limit.coerceIn(1, 50)
                )
            }

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Search failed (${response.code()})"))
            }

            val repositories = response.body().orEmpty()
                .mapNotNull { repo ->
                    val repoId = repo.id
                    if (repoId.isBlank() || !repoId.contains("/")) return@mapNotNull null
                    HuggingFaceExplorerRepo(
                        id = repoId,
                        author = repo.author ?: repoId.substringBefore("/"),
                        downloads = repo.downloads ?: 0L,
                        likes = repo.likes ?: 0L,
                        gated = repo.gated ?: false,
                        tags = repo.tags.orEmpty().filter { it.isNotBlank() }.take(6)
                    )
                }
                .distinctBy { it.id }

            Result.success(repositories)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch trending GGUF models directly from HuggingFace API using trendingScore sorting.
     */
    suspend fun fetchTrendingGgufModels(limit: Int = 30): Result<List<HuggingFaceExplorerRepo>> = withContext(Dispatchers.IO) {
        try {
            val response = api.searchModels(
                filter = "gguf",
                search = null,
                sort = "trendingScore",
                direction = -1,
                limit = limit.coerceIn(1, 50)
            )

            if (!response.isSuccessful) {
                return@withContext Result.failure(Exception("Failed to fetch trending models (${response.code()})"))
            }

            val repositories = response.body().orEmpty()
                .mapNotNull { repo ->
                    val repoId = repo.id
                    if (repoId.isBlank() || !repoId.contains("/")) return@mapNotNull null
                    HuggingFaceExplorerRepo(
                        id = repoId,
                        author = repo.author ?: repoId.substringBefore("/"),
                        downloads = repo.downloads ?: 0L,
                        likes = repo.likes ?: 0L,
                        gated = repo.gated ?: false,
                        tags = repo.tags.orEmpty().filter { it.isNotBlank() }.take(6)
                    )
                }
                .distinctBy { it.id }

            Result.success(repositories)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fast single-call file resolution using api/models/{repo} siblings metadata.
     * Prevents 10+ second timeouts from recursive git tree scanning.
     */
    suspend fun fetchRepoFilesFast(repoPath: String): List<HuggingFaceFileResponse> = withContext(Dispatchers.IO) {
        try {
            val infoResponse = api.getRepoInfo(repoPath)
            if (infoResponse.isSuccessful) {
                val siblings = infoResponse.body()?.siblings.orEmpty()
                if (siblings.isNotEmpty()) {
                    val files = siblings.filter { it.path.isNotBlank() }
                    if (files.any { it.path.endsWith(".gguf", ignoreCase = true) }) {
                        return@withContext files
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("HFExplorer", "Fast repo info failed for $repoPath, trying tree fallback", e)
        }

        // Fallback to recursive tree if siblings unavailable
        try {
            val treeResponse = api.getRepoFiles(repoPath, recursive = true)
            if (treeResponse.isSuccessful) {
                return@withContext treeResponse.body().orEmpty()
            }
        } catch (e: Exception) {
            android.util.Log.e("HFExplorer", "Tree fetch failed for $repoPath", e)
        }

        emptyList()
    }
}
