package com.bit.modelrouter

import com.bit.modelrouter.loader.ModelLoader

class ModelAutoLoadService(
    private val router: ModelRouter,
    private val loaders: List<ModelLoader>
) {
    suspend fun onModelImported(manifest: ModelManifest): Result<RoutingDecision> {
        val decision = router.route(manifest)
        if (decision.target == RouteTarget.NONE) {
            return Result.success(decision) // keep as unsupported, do not fail
        }

        val loader = loaders.firstOrNull { it.supports(decision.task) }
            ?: return Result.failure(IllegalStateException("No loader for task ${decision.task}"))

        return loader.load(manifest).map { decision }
    }
}
