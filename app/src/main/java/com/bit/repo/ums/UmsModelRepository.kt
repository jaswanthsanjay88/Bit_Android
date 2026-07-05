package com.bit.repo.ums

import com.bit.data.Tags
import com.bit.data.UmsCollections
import com.bit.models.enums.PathType
import com.bit.models.enums.ProviderType
import com.bit.models.table_schema.Model
import com.dark.ums.UmsRecord
import com.dark.ums.UnifiedMemorySystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

class UmsModelRepository(private val ums: UnifiedMemorySystem) {

    private val collection = UmsCollections.MODELS
    private val _allModels = MutableStateFlow<List<Model>>(emptyList())

    fun init() {
        ums.ensureCollection(collection)
        ums.addIndex(collection, Tags.Model.ENTITY_ID, UnifiedMemorySystem.WIRE_BYTES)
        ums.addIndex(collection, Tags.Model.PROVIDER_TYPE, UnifiedMemorySystem.WIRE_BYTES)
        refreshCache()
    }

    private fun refreshCache() {
        val allRecords = ums.getAll(collection)
        val seenIds = mutableSetOf<String>()
        val dedupedModels = mutableListOf<Model>()
        
        allRecords.forEach { record ->
            val model = record.toModel()
            if (model.id.isNotEmpty()) {
                if (model.id in seenIds) {
                    // Delete duplicate record from database
                    ums.delete(collection, record.id)
                } else {
                    seenIds.add(model.id)
                    dedupedModels.add(model)
                }
            }
        }
        _allModels.value = dedupedModels
    }

    suspend fun insert(model: Model) = withContext(Dispatchers.IO) {
        val existing = findRecordId(model.id)
        if (existing != null) {
            ums.put(collection, model.toRecord(existing))
        } else {
            ums.put(collection, model.toRecord())
        }
        refreshCache()
    }

    suspend fun update(model: Model) = withContext(Dispatchers.IO) {
        val existing = findRecordId(model.id) ?: return@withContext
        ums.put(collection, model.toRecord(existing))
        refreshCache()
    }

    suspend fun delete(model: Model) = withContext(Dispatchers.IO) {
        val recordId = findRecordId(model.id) ?: return@withContext
        ums.delete(collection, recordId)
        refreshCache()
    }

    suspend fun getById(id: String): Model? = withContext(Dispatchers.IO) {
        ums.queryString(collection, Tags.Model.ENTITY_ID, id)
            .firstOrNull()?.toModel()
    }

    fun getAll(): Flow<List<Model>> = _allModels

    suspend fun getAllOnce(): List<Model> = withContext(Dispatchers.IO) {
        ums.getAll(collection).map { it.toModel() }
    }

    suspend fun updateActiveStatus(id: String, isActive: Boolean) = withContext(Dispatchers.IO) {
        val model = getById(id) ?: return@withContext
        update(model.copy(isActive = isActive))
    }

    private fun findRecordId(entityId: String): Int? {
        return ums.queryString(collection, Tags.Model.ENTITY_ID, entityId)
            .firstOrNull()?.id?.takeIf { it != 0 }
    }
}

private fun Model.toRecord(existingId: Int = 0): UmsRecord {
    val b = UmsRecord.create()
    if (existingId != 0) b.id(existingId)
    b.putString(Tags.Model.ENTITY_ID, id)
    b.putString(Tags.Model.MODEL_NAME, modelName)
    b.putString(Tags.Model.MODEL_PATH, modelPath)
    b.putString(Tags.Model.PATH_TYPE, pathType.name)
    b.putString(Tags.Model.PROVIDER_TYPE, providerType.name)
    if (fileSize != null) b.putTimestamp(Tags.Model.FILE_SIZE, fileSize)
    b.putBool(Tags.Model.IS_ACTIVE, isActive)
    return b.build()
}

private fun UmsRecord.toModel(): Model = Model(
    id = getString(Tags.Model.ENTITY_ID) ?: "",
    modelName = getString(Tags.Model.MODEL_NAME) ?: "",
    modelPath = getString(Tags.Model.MODEL_PATH) ?: "",
    pathType = getString(Tags.Model.PATH_TYPE)?.let { runCatching { PathType.valueOf(it) }.getOrNull() } ?: PathType.FILE,
    providerType = getString(Tags.Model.PROVIDER_TYPE)?.let { runCatching { ProviderType.valueOf(it) }.getOrNull() } ?: ProviderType.GGUF,
    fileSize = getTimestamp(Tags.Model.FILE_SIZE),
    isActive = getBool(Tags.Model.IS_ACTIVE) ?: true
)
