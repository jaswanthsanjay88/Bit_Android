package com.bit.repo

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.bit.models.data.HFModelRepository
import com.bit.models.data.ModelCategory
import com.bit.models.data.ModelType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.modelRepoDataStore: DataStore<Preferences> by preferencesDataStore(name = "model_repositories")

class ModelRepositoryDataStore(private val context: Context) {

    companion object {
        private val MODEL_REPOS_KEY = stringPreferencesKey("model_repositories")
        private val DELETED_DEFAULTS_KEY = stringPreferencesKey("deleted_default_repo_ids")

        val DEFAULT_REPOSITORIES = listOf(
            // === IMAGE GENERATION (SD) ===
            HFModelRepository(
                id = "sd-qnn",
                name = "Stable Diffusion (NPU)",
                repoPath = "xororz/sd-qnn",
                modelType = ModelType.SD,
                isEnabled = false,
                category = ModelCategory.GENERAL
            ),
            HFModelRepository(
                id = "sd-mnn",
                name = "Stable Diffusion (CPU)",
                repoPath = "xororz/sd-mnn",
                modelType = ModelType.SD,
                isEnabled = false,
                category = ModelCategory.GENERAL
            ),
            HFModelRepository(
                id = "vits-piper-en_US-amy-low",
                name = "Piper US Amy (TTS)",
                repoPath = "csukuangfj/vits-piper-en_US-amy-low",
                modelType = ModelType.TTS,
                isEnabled = false,
                category = ModelCategory.GENERAL
            ),

            HFModelRepository(
                id = "sherpa-whisper-tiny",
                name = "Whisper Tiny (STT)",
                repoPath = "csukuangfj/sherpa-onnx-whisper-tiny.en",
                modelType = ModelType.STT,
                isEnabled = false,
                category = ModelCategory.GENERAL
            )
        )
    }

    val repositories: Flow<List<HFModelRepository>> =
        context.modelRepoDataStore.data.map { preferences ->
            val json = preferences[MODEL_REPOS_KEY]
            val deletedJson = preferences[DELETED_DEFAULTS_KEY]
            val deletedIds = deletedJson?.let {
                try { Json.decodeFromString<Set<String>>(it) } catch (_: Exception) { emptySet() }
            } ?: emptySet()

            if (json != null) {
                try {
                    val saved = Json.decodeFromString<List<HFModelRepository>>(json)
                    val savedIds = saved.map { it.id }.toSet()
                    val newDefaults = DEFAULT_REPOSITORIES.filter {
                        it.id !in savedIds && it.id !in deletedIds
                    }
                    if (newDefaults.isNotEmpty()) saved + newDefaults else saved
                } catch (e: Exception) {
                    DEFAULT_REPOSITORIES
                }
            } else {
                DEFAULT_REPOSITORIES
            }
        }

    suspend fun saveRepositories(repos: List<HFModelRepository>) {
        context.modelRepoDataStore.edit { preferences ->
            preferences[MODEL_REPOS_KEY] = Json.encodeToString(repos)
        }
    }

    suspend fun addRepository(repo: HFModelRepository) {
        val current = repositories.first()
        saveRepositories(current + repo)
    }

    suspend fun removeRepository(repoId: String) {
        val current = repositories.first()
        saveRepositories(current.filterNot { it.id == repoId })
        if (DEFAULT_REPOSITORIES.any { it.id == repoId }) {
            context.modelRepoDataStore.edit { preferences ->
                val existing = preferences[DELETED_DEFAULTS_KEY]?.let {
                    try { Json.decodeFromString<Set<String>>(it) } catch (_: Exception) { emptySet() }
                } ?: emptySet()
                preferences[DELETED_DEFAULTS_KEY] = Json.encodeToString(existing + repoId)
            }
        }
    }

    suspend fun toggleRepository(repoId: String) {
        val current = repositories.first()
        saveRepositories(current.map {
            if (it.id == repoId) it.copy(isEnabled = !it.isEnabled)
            else it
        })
    }

    suspend fun updateRepository(repo: HFModelRepository) {
        val current = repositories.first()
        saveRepositories(current.map {
            if (it.id == repo.id) repo else it
        })
    }
}
