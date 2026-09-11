package com.bit.agent.harness.tools

import com.bit.agent.harness.model.ToolObservation
import com.bit.api.ToolDefinition
import com.bit.api.ToolFunction
import com.bit.api.ToolParameters
import com.bit.api.ToolProperty
import com.bit.data.AiMemoryWriter
import com.bit.database.dao.MemoryNoteDao
import com.bit.di.AppContainer
import com.bit.models.table_schema.MemoryCategory
import org.json.JSONArray
import org.json.JSONObject

/**
 * Tools for creating, updating, querying, and deleting user memory notes in BIT's UMS Memory Vault.
 */
class CreateMemoryTool(private val aiMemoryWriter: AiMemoryWriter) : AgentTool {
    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = "create_memory",
            description = "Save an important fact, user preference, or permanent note into the persistent Memory Vault (UMS & RAG).",
            parameters = ToolParameters(
                properties = mapOf(
                    "content" to ToolProperty(type = "string", description = "Detailed memory text or fact to remember"),
                    "title" to ToolProperty(type = "string", description = "Optional brief title for the memory note"),
                    "category" to ToolProperty(type = "string", description = "Optional category: 'personal', 'preference', 'work', 'interest', or 'general'")
                ),
                required = listOf("content")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = JSONObject(argumentsJson)
            val content = args.optString("content", "").trim()
            val title = args.optString("title", "").ifBlank { null }
            val catStr = args.optString("category", "general").lowercase()

            if (content.isBlank()) {
                return ToolObservation.error("Memory content cannot be blank", "Provide informative content to store.")
            }

            val category = when (catStr) {
                "personal" -> MemoryCategory.PERSONAL
                "preference" -> MemoryCategory.PREFERENCE
                "work" -> MemoryCategory.WORK
                "interest" -> MemoryCategory.INTEREST
                else -> MemoryCategory.GENERAL
            }

            val saved = aiMemoryWriter.saveOrUpdateAiMemory(
                text = content,
                title = title,
                category = category
            )

            ToolObservation.success(
                summary = "Remembered fact: '${saved.fact.take(40)}...'",
                payload = "Memory persisted in UMS & RAG with ID: ${saved.id}",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            ToolObservation.error(
                summary = "Failed to create memory note: ${e.message}",
                recoveryHint = "Retry saving memory with simplified text.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}

class UpdateMemoryTool(private val aiMemoryWriter: AiMemoryWriter) : AgentTool {
    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = "update_memory",
            description = "Update or correct an existing memory or fact in the Memory Vault using its memory ID.",
            parameters = ToolParameters(
                properties = mapOf(
                    "id" to ToolProperty(type = "string", description = "Unique ID of the memory to update"),
                    "new_content" to ToolProperty(type = "string", description = "Updated memory text or corrected fact"),
                    "category" to ToolProperty(type = "string", description = "Optional category: 'personal', 'preference', 'work', 'interest', or 'general'")
                ),
                required = listOf("id", "new_content")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = JSONObject(argumentsJson)
            val id = args.optString("id", "").trim()
            val newContent = args.optString("new_content", "").trim()
            val catStr = args.optString("category", "general").lowercase()

            if (id.isBlank() || newContent.isBlank()) {
                return ToolObservation.error(
                    summary = "Both 'id' and 'new_content' are required.",
                    recoveryHint = "Provide a valid memory ID and updated text."
                )
            }

            val category = when (catStr) {
                "personal" -> MemoryCategory.PERSONAL
                "preference" -> MemoryCategory.PREFERENCE
                "work" -> MemoryCategory.WORK
                "interest" -> MemoryCategory.INTEREST
                else -> MemoryCategory.GENERAL
            }

            val ok = aiMemoryWriter.updateMemory(id, newContent, category)
            if (ok) {
                ToolObservation.success(
                    summary = "Updated memory $id",
                    payload = "Memory successfully updated in UMS and re-indexed in RAG.",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            } else {
                ToolObservation.error(
                    summary = "Memory ID '$id' not found in vault",
                    recoveryHint = "Query memories first using query_memory to find the correct ID.",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: Exception) {
            ToolObservation.error(
                summary = "Failed to update memory: ${e.message}",
                recoveryHint = "Verify arguments and retry.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}

class DeleteMemoryTool(private val aiMemoryWriter: AiMemoryWriter) : AgentTool {
    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = "delete_memory",
            description = "Delete or forget a memory or fact from the Memory Vault using its memory ID.",
            parameters = ToolParameters(
                properties = mapOf(
                    "id" to ToolProperty(type = "string", description = "Unique ID of the memory to delete")
                ),
                required = listOf("id")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = JSONObject(argumentsJson)
            val id = args.optString("id", "").trim()

            if (id.isBlank()) {
                return ToolObservation.error(
                    summary = "'id' is required to delete a memory.",
                    recoveryHint = "Provide a valid memory ID to delete."
                )
            }

            val ok = aiMemoryWriter.deleteMemory(id)
            if (ok) {
                ToolObservation.success(
                    summary = "Deleted memory $id",
                    payload = "Memory permanently deleted from UMS and evicted from RAG.",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            } else {
                ToolObservation.error(
                    summary = "Failed to delete memory $id (not found)",
                    recoveryHint = "Query memories first with query_memory to find the ID.",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: Exception) {
            ToolObservation.error(
                summary = "Failed to delete memory: ${e.message}",
                recoveryHint = "Retry deleting with a valid ID.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}

class QueryMemoryTool(private val memoryNoteDao: MemoryNoteDao? = null) : AgentTool {
    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = "query_memory",
            description = "Query or search across saved memory notes and facts in the user's Memory Vault (UMS & RAG).",
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ToolProperty(type = "string", description = "Keyword or semantic search query"),
                    "top_k" to ToolProperty(type = "integer", description = "Maximum number of memories to retrieve (default: 5)")
                ),
                required = listOf("query")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = JSONObject(argumentsJson)
            val query = args.optString("query", "").trim().lowercase()
            val topK = args.optInt("top_k", 5).coerceIn(1, 20)

            val array = JSONArray()

            // Query UMS (Single Source of Truth)
            val memoryRepo = runCatching { AppContainer.getMemoryRepo() }.getOrNull()
            val umsMemories = memoryRepo?.getAllOnce().orEmpty()

            val matchedUms = if (query.isBlank()) {
                umsMemories.take(topK)
            } else {
                umsMemories.filter { it.fact.contains(query, ignoreCase = true) }.take(topK)
            }

            matchedUms.forEach { mem ->
                array.put(JSONObject().apply {
                    put("id", mem.id)
                    put("fact", mem.fact)
                    put("category", mem.category.name)
                    put("updated_at", mem.updatedAt)
                })
            }

            // Also check legacy room notes if UMS had fewer than topK
            if (array.length() < topK && memoryNoteDao != null) {
                val notes = memoryNoteDao.getAllNotesOnce()
                val matchedNotes = notes.filter { n ->
                    (query.isBlank() || n.title.contains(query, ignoreCase = true) || n.content.contains(query, ignoreCase = true)) &&
                    (0 until array.length()).none { array.getJSONObject(it).optString("id") == n.id }
                }.take(topK - array.length())

                matchedNotes.forEach { n ->
                    array.put(JSONObject().apply {
                        put("id", n.id)
                        put("fact", n.content)
                        put("category", n.folder)
                        put("updated_at", n.updatedAt)
                    })
                }
            }

            ToolObservation.success(
                summary = "Found ${array.length()} matching memories for query '$query'",
                payload = array.toString(),
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            ToolObservation.error(
                summary = "Failed to query memories: ${e.message}",
                recoveryHint = "Try searching with different keywords.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}

