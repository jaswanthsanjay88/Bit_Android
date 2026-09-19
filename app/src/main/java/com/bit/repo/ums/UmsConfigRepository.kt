package com.bit.repo.ums

import com.bit.data.Tags
import com.bit.data.UmsCollections
import com.bit.models.table_schema.ModelConfig
import com.dark.ums.UmsRecord
import com.dark.ums.UnifiedMemorySystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UmsConfigRepository(private val ums: UnifiedMemorySystem) {

    private val collection = UmsCollections.MODEL_CONFIG

    fun init() {
        ums.ensureCollection(collection)
        ums.addIndex(collection, Tags.Config.ENTITY_ID, UnifiedMemorySystem.WIRE_BYTES)
        ums.addIndex(collection, Tags.Config.MODEL_ID, UnifiedMemorySystem.WIRE_BYTES)
    }

    suspend fun insert(config: ModelConfig) = withContext(Dispatchers.IO) {
        val existingRecordId = findRecordIdByModelId(config.modelId) ?: findRecordId(config.id)
        if (existingRecordId != null) {
            val allForModel = ums.queryString(collection, Tags.Config.MODEL_ID, config.modelId)
            allForModel.forEach { rec ->
                if (rec.id != existingRecordId && rec.id != 0) {
                    ums.delete(collection, rec.id)
                }
            }
            ums.put(collection, config.toRecord(existingRecordId))
        } else {
            ums.put(collection, config.toRecord())
        }
    }

    suspend fun update(config: ModelConfig) = withContext(Dispatchers.IO) {
        val existingRecordId = findRecordIdByModelId(config.modelId) ?: findRecordId(config.id)
        if (existingRecordId != null) {
            val allForModel = ums.queryString(collection, Tags.Config.MODEL_ID, config.modelId)
            allForModel.forEach { rec ->
                if (rec.id != existingRecordId && rec.id != 0) {
                    ums.delete(collection, rec.id)
                }
            }
            ums.put(collection, config.toRecord(existingRecordId))
        } else {
            ums.put(collection, config.toRecord())
        }
    }

    suspend fun delete(config: ModelConfig) = withContext(Dispatchers.IO) {
        val records = (ums.queryString(collection, Tags.Config.MODEL_ID, config.modelId) +
            ums.queryString(collection, Tags.Config.ENTITY_ID, config.id)).distinctBy { it.id }
        records.forEach { rec ->
            if (rec.id != 0) ums.delete(collection, rec.id)
        }
    }

    suspend fun getByModelId(modelId: String): ModelConfig? = withContext(Dispatchers.IO) {
        val records = ums.queryString(collection, Tags.Config.MODEL_ID, modelId)
        if (records.isEmpty()) return@withContext null
        if (records.size > 1) {
            // Self-healing: Clean up historical duplicates left by previous bug, preserving latest
            val latest = records.last()
            records.dropLast(1).forEach { old ->
                if (old.id != 0) ums.delete(collection, old.id)
            }
            latest.toModelConfig()
        } else {
            records.first().toModelConfig()
        }
    }

    suspend fun getById(id: String): ModelConfig? = withContext(Dispatchers.IO) {
        ums.queryString(collection, Tags.Config.ENTITY_ID, id)
            .lastOrNull()?.toModelConfig()
    }

    private fun findRecordIdByModelId(modelId: String): Int? {
        return ums.queryString(collection, Tags.Config.MODEL_ID, modelId)
            .lastOrNull()?.id?.takeIf { it != 0 }
    }

    private fun findRecordId(entityId: String): Int? {
        return ums.queryString(collection, Tags.Config.ENTITY_ID, entityId)
            .lastOrNull()?.id?.takeIf { it != 0 }
    }
}

private fun ModelConfig.toRecord(existingId: Int = 0): UmsRecord {
    val b = UmsRecord.create()
    if (existingId != 0) b.id(existingId)
    b.putString(Tags.Config.ENTITY_ID, id)
    b.putString(Tags.Config.MODEL_ID, modelId)
    if (modelLoadingParams != null) b.putString(Tags.Config.LOADING_PARAMS, modelLoadingParams)
    if (modelInferenceParams != null) b.putString(Tags.Config.INFERENCE_PARAMS, modelInferenceParams)
    return b.build()
}

private fun UmsRecord.toModelConfig(): ModelConfig = ModelConfig(
    id = getString(Tags.Config.ENTITY_ID) ?: "",
    modelId = getString(Tags.Config.MODEL_ID) ?: "",
    modelLoadingParams = getString(Tags.Config.LOADING_PARAMS),
    modelInferenceParams = getString(Tags.Config.INFERENCE_PARAMS)
)
