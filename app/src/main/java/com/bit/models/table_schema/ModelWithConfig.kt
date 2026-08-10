package com.bit.models.table_schema

import com.bit.models.engine_schema.GgufEngineSchema

fun GgufEngineSchema.toModelConfig(modelId: String): ModelConfig {
    return ModelConfig(
        modelId = modelId,
        modelLoadingParams = toLoadingJson(),
        modelInferenceParams = toInferenceJson()
    )
}
