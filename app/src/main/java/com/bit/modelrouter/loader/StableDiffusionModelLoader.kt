package com.bit.modelrouter.loader

import com.bit.modelrouter.ModelManifest
import com.bit.modelrouter.ModelTask

class StableDiffusionModelLoader : ModelLoader {
    override fun supports(task: ModelTask): Boolean {
        return task == ModelTask.SD_TEXT2IMG ||
               task == ModelTask.SD_IMG2IMG ||
               task == ModelTask.SD_INPAINT ||
               task == ModelTask.SD_UPSCALE
    }

    override suspend fun load(manifest: ModelManifest): Result<Unit> = runCatching {
        // TODO: Call ai_sd APIs
    }

    override suspend fun unload(modelId: String): Result<Unit> = runCatching {
        // TODO: Call ai_sd unload
    }
}
