package com.bit.modelrouter

data class RoutingDecision(
    val task: ModelTask,
    val target: RouteTarget,
    val reason: String
)

class ModelRouter(
    private val classifier: ModelClassifier
) {
    fun route(manifest: ModelManifest): RoutingDecision {
        val task = classifier.classify(manifest)
        val target = when (task) {
            ModelTask.TEXT_GENERATION,
            ModelTask.EMBEDDING,
            ModelTask.VLM -> RouteTarget.GGUF_LIB

            ModelTask.TTS,
            ModelTask.STT -> RouteTarget.AI_SHERPA

            ModelTask.SD_TEXT2IMG,
            ModelTask.SD_IMG2IMG,
            ModelTask.SD_INPAINT,
            ModelTask.SD_UPSCALE -> RouteTarget.AI_SD

            ModelTask.UNSUPPORTED -> RouteTarget.NONE
        }

        return RoutingDecision(
            task = task,
            target = target,
            reason = "Auto-routed by classifier"
        )
    }
}
