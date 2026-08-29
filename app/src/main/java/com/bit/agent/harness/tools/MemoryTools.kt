package com.bit.agent.harness.tools

import com.bit.agent.harness.model.ToolObservation
import com.bit.api.ToolDefinition
import com.bit.api.ToolFunction
import com.bit.api.ToolParameters
import com.bit.api.ToolProperty
import com.bit.database.dao.MemoryNoteDao
import com.bit.models.table_schema.MemoryNote
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Tools for creating, updating, and querying user memory notes in BIT's Memory Vault.
 */
class CreateMemoryTool(private val memoryNoteDao: MemoryNoteDao) : AgentTool {
    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = "create_memory",
            description = "Save an important fact, user preference, or permanent note into the persistent Memory Vault.",
            parameters = ToolParameters(
                properties = mapOf(
                    "title" to ToolProperty(type = "string", description = "Brief title for the memory note"),
                    "content" to ToolProperty(type = "string", description = "Detailed memory text or fact to remember"),
                    "folder" to ToolProperty(type = "string", description = "Optional folder category (e.g. personal, work, preferences)")
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
            val title = args.optString("title", "").ifBlank { "Note ${System.currentTimeMillis()}" }
            val folder = args.optString("folder", "General")

            if (content.isBlank()) {
                return ToolObservation.error("Memory content cannot be blank", "Provide informative content to store.")
            }

            val note = MemoryNote(
                id = UUID.randomUUID().toString(),
                title = title,
                content = content,
                folder = folder,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                isAiMemoryEnabled = true
            )
            memoryNoteDao.insertNote(note)

            ToolObservation.success(
                summary = "Created memory note: '$title'",
                payload = "Memory saved with ID ${note.id}",
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

class QueryMemoryTool(private val memoryNoteDao: MemoryNoteDao) : AgentTool {
    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = "query_memory",
            description = "Query or search across saved memory notes and facts in the user's Memory Vault.",
            parameters = ToolParameters(
                properties = mapOf(
                    "query" to ToolProperty(type = "string", description = "Keyword or semantic search query"),
                    "top_k" to ToolProperty(type = "integer", description = "Maximum number of notes to retrieve (default: 5)")
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

            val notes = memoryNoteDao.getAllNotesOnce()
            val matched = if (query.isBlank()) {
                notes.take(topK)
            } else {
                notes.filter {
                    it.title.lowercase().contains(query) || it.content.lowercase().contains(query)
                }.take(topK)
            }

            val array = JSONArray()
            matched.forEach { n ->
                array.put(JSONObject().apply {
                    put("id", n.id)
                    put("title", n.title)
                    put("content", n.content)
                    put("folder", n.folder)
                    put("updated_at", n.updatedAt)
                })
            }

            ToolObservation.success(
                summary = "Found ${matched.size} matching memory notes for query '$query'",
                payload = array.toString(),
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            ToolObservation.error(
                summary = "Failed to query memory notes: ${e.message}",
                recoveryHint = "Try searching with different keywords.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}
