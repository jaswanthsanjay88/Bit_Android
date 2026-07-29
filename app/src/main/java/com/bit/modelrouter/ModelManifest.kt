package com.bit.modelrouter

data class ModelManifest(
    val id: String,
    val name: String,
    val localPath: String,
    val format: String? = null,          // gguf, onnx, tflite, etc.
    val family: String? = null,          // llama, mistral, whisper, kokoro, sdxl...
    val declaredTask: String? = null,    // optional task from metadata
    val capabilities: Set<String> = emptySet(),
    val locales: List<String> = emptyList()
)
