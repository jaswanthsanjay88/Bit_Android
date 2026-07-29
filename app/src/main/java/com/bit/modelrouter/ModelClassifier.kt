package com.bit.modelrouter

class ModelClassifier {

    fun classify(manifest: ModelManifest): ModelTask {
        // 1) explicit declared task (highest priority)
        manifest.declaredTask?.lowercase()?.let { task ->
            mapDeclaredTask(task)?.let { return it }
        }

        // 2) capability hints
        val caps = manifest.capabilities.map { it.lowercase() }.toSet()
        if ("text-generation" in caps || "chat" in caps) return ModelTask.TEXT_GENERATION
        if ("embedding" in caps || "embeddings" in caps) return ModelTask.EMBEDDING
        if ("vision-language" in caps || "vlm" in caps) return ModelTask.VLM
        if ("tts" in caps) return ModelTask.TTS
        if ("stt" in caps || "asr" in caps) return ModelTask.STT
        if ("text-to-image" in caps || "t2i" in caps) return ModelTask.SD_TEXT2IMG
        if ("img2img" in caps) return ModelTask.SD_IMG2IMG
        if ("inpaint" in caps) return ModelTask.SD_INPAINT
        if ("upscale" in caps) return ModelTask.SD_UPSCALE

        // 3) filename / path heuristic
        val p = manifest.localPath.lowercase()
        if (p.endsWith(".gguf")) {
            if (p.contains("embed")) return ModelTask.EMBEDDING
            if (p.contains("vision") || p.contains("vlm")) return ModelTask.VLM
            return ModelTask.TEXT_GENERATION
        }
        if (p.contains("whisper")) return ModelTask.STT
        if (p.contains("kokoro") || p.contains("vits") || p.contains("tts")) return ModelTask.TTS
        if (p.contains("sdxl") || p.contains("stable-diffusion") || p.contains("unet")) return ModelTask.SD_TEXT2IMG

        return ModelTask.UNSUPPORTED
    }

    private fun mapDeclaredTask(task: String): ModelTask? {
        return when (task) {
            "text-generation", "chat", "completion", "causal-lm" -> ModelTask.TEXT_GENERATION
            "embedding", "embeddings", "feature-extraction" -> ModelTask.EMBEDDING
            "vision-language", "vlm", "image-text-to-text" -> ModelTask.VLM
            "tts", "text-to-speech" -> ModelTask.TTS
            "stt", "asr", "speech-to-text", "automatic-speech-recognition" -> ModelTask.STT
            "text-to-image", "txt2img", "t2i" -> ModelTask.SD_TEXT2IMG
            "img2img", "image-to-image" -> ModelTask.SD_IMG2IMG
            "inpaint", "inpainting" -> ModelTask.SD_INPAINT
            "upscale", "super-resolution" -> ModelTask.SD_UPSCALE
            else -> null
        }
    }
}
