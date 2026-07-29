package com.bit.modelrouter.loader

import com.bit.modelrouter.ModelManifest
import com.bit.modelrouter.ModelTask

interface ModelLoader {
    fun supports(task: ModelTask): Boolean
    suspend fun load(manifest: ModelManifest): Result<Unit>
    suspend fun unload(modelId: String): Result<Unit>
}
