package com.bit.modelrouter.loader

import com.bit.modelrouter.ModelManifest
import com.bit.modelrouter.ModelTask

class GgufModelLoader : ModelLoader {
    override fun supports(task: ModelTask): Boolean {
        return task == ModelTask.TEXT_GENERATION ||
               task == ModelTask.EMBEDDING ||
               task == ModelTask.VLM
    }

    override suspend fun load(manifest: ModelManifest): Result<Unit> = runCatching {
        // TODO: Call gguf_lib APIs here
        // ggufEngine.loadModel(manifest.localPath)
    }

    override suspend fun unload(modelId: String): Result<Unit> = runCatching {
        // TODO: Call gguf_lib unload
    }
}
