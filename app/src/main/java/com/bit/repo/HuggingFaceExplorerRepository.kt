package com.bit.repo
import com.bit.domain.repository.HuggingFaceExplorerContract
import com.bit.network.HuggingFaceApi
import com.bit.network.HuggingFaceClient
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

    // Trigger rebuild to clear KSP incremental compilation issues
    override suspend fun searchGgufRepositories(query: String, limit: Int): Result<List<HuggingFaceExplorerRepo>> = withContext(Dispatchers.IO) {
        try {
            val response = api.searchModels(
                filter = null,
                search = query.trim(),
                sort = "downloads",
                direction = -1,
                limit = limit.coerceIn(1, 50)
            )

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
}
