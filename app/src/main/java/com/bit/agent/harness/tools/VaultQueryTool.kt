package com.bit.agent.harness.tools

import com.bit.api.ToolDefinition
import com.bit.api.ToolFunction
import com.bit.api.ToolParameters
import com.bit.api.ToolProperty
import com.bit.agent.harness.HarnessLogger
import com.bit.agent.harness.NoOpHarnessLogger
import com.bit.agent.harness.model.ToolObservation
import com.bit.worker.GlobalRagOrchestrator
import org.json.JSONObject

/**
 * Semantic search over the user's memory vault via the global RAG graph.
 * Satisfies the spec's vault_query / rag_search_docs requirement.
 */
class VaultQueryTool(
    private val ragOrchestrator: GlobalRagOrchestrator,
    private val logger: HarnessLogger = NoOpHarnessLogger
) : AgentTool {

    companion object {
        const val NAME = "vault_query"
    }

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = NAME,
            description = "Semantic search over the user's memory vault and attached documents. " +
                "Returns the most relevant knowledge chunks for a natural-language query.",
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ToolProperty(
                        type = "string",
                        description = "Natural-language search query"
                    ),
                    "top_k" to ToolProperty(
                        type = "integer",
                        description = "Maximum number of knowledge chunks to return (default 5)"
                    )
                ),
                required = listOf("query")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
            val query = args.optString("query").trim()
            if (query.isEmpty()) {
                return ToolObservation.error(
                    summary = "vault_query requires a non-empty 'query' argument.",
                    recoveryHint = "Provide a natural-language query string."
                )
            }
            val topK = args.optInt("top_k", 5).coerceIn(1, 20)

            val result = ragOrchestrator.queryGlobalKnowledge(query, topK)
            val duration = System.currentTimeMillis() - startTime

            if (result == null || result.results.isEmpty()) {
                ToolObservation.warning(
                    summary = "No vault knowledge found for: $query",
                    recoveryHint = "Rephrase the query or note that the vault may be empty.",
                    payload = result?.compressedContext,
                    executionTimeMs = duration
                )
            } else {
                ToolObservation.success(
                    summary = "Found ${result.results.size} vault chunks (confidence: ${result.confidence}).",
                    payload = result.compressedContext,
                    executionTimeMs = duration
                )
            }
        } catch (e: Exception) {
            logger.e("VaultQueryTool", "vault_query failed: ${e.message}", e)
            ToolObservation.error(
                summary = "Vault query failed: ${e.message ?: "unknown error"}",
                recoveryHint = "The embedding engine may not be initialized; retry after the vault finishes indexing.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}
