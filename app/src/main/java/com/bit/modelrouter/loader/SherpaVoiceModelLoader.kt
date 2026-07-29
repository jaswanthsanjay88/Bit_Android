package com.bit.modelrouter.loader

import com.bit.modelrouter.ModelManifest
import com.bit.modelrouter.ModelTask

class SherpaVoiceModelLoader : ModelLoader {
    override fun supports(task: ModelTask): Boolean {
        return task == ModelTask.TTS || task == ModelTask.STT
    }

    override suspend fun load(manifest: ModelManifest): Result<Unit> = runCatching {
        // TODO: Call ai_sherpa APIs
    }

    override suspend fun unload(modelId: String): Result<Unit> = runCatching {
        // TODO: Call ai_sherpa unload
    }
}
