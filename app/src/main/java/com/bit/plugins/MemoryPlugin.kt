package com.bit.plugins

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bit.global.AppPaths
import com.bit.models.plugins.PluginInfo
import com.bit.plugins.api.SuperPlugin
import com.dark.gguf_lib.toolcalling.ToolCall
import com.dark.gguf_lib.toolcalling.ToolDefinitionBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.bit.worker.GlobalRagOrchestrator
import com.bit.models.table_schema.MemoryNote
import com.bit.data.VaultFileStore
import com.bit.database.dao.MemoryNoteDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MemoryPluginEntryPoint {
    fun globalRagOrchestrator(): GlobalRagOrchestrator
    fun vaultFileStore(): VaultFileStore
    fun memoryNoteDao(): MemoryNoteDao
}

class MemoryPlugin(private val context: Context) : SuperPlugin {

    companion object {
        private const val TAG = "MemoryPlugin"
        const val TOOL_LIST_MEMORY = "list_memory_files"
        const val TOOL_READ_MEMORY = "read_memory_file"
        const val TOOL_CREATE_MEMORY = "create_memory_file"
        const val TOOL_EDIT_MEMORY = "edit_memory_file"
        const val TOOL_DELETE_MEMORY = "delete_memory_file"
    }

    override fun getPluginInfo(): PluginInfo {
        return PluginInfo(
            name = "Memory Vault",
            description = "Read, write, and manage memory files in the BIT Vault.",
            author = "BIT",
            version = "1.0.0",
            toolDefinitionBuilder = listOf(
                ToolDefinitionBuilder(
                    TOOL_LIST_MEMORY,
                    "List all files in the memory vault."
                ),
                ToolDefinitionBuilder(
                    TOOL_READ_MEMORY,
                    "Read the content of a file from the memory vault."
                )
                    .stringParam("name", "The file name to read (e.g., 'notes.md').", required = true),
                ToolDefinitionBuilder(
                    TOOL_CREATE_MEMORY,
                    "Create a new file in the memory vault with the given content."
                )
                    .stringParam("name", "The file name to create (e.g., 'notes.md').", required = true)
                    .stringParam("content", "The markdown content for the file.", required = true),
                ToolDefinitionBuilder(
                    TOOL_EDIT_MEMORY,
                    "Edit a file in the memory vault."
                )
                    .stringParam("name", "The current file name to edit.", required = true)
                    .stringParam("content", "The new markdown content (full rewrite). Omit to keep existing.", required = false)
                    .stringParam("old_string", "Exact string to find and replace. Mutually exclusive with 'content'.", required = false)
                    .stringParam("new_string", "Replacement string for old_string.", required = false),
                ToolDefinitionBuilder(
                    TOOL_DELETE_MEMORY,
                    "Delete a file from the memory vault."
                )
                    .stringParam("name", "The file name to delete.", required = true)
            )
        )
    }

    override fun serializeResult(data: Any): String {
        return when (data) {
            is JSONObject -> data.toString()
            is JSONArray -> data.toString()
            else -> data.toString()
        }
    }

    private fun findFileInVault(vaultDir: File, name: String): File? {
        val cleanName = name.trim().removePrefix("/").removePrefix("\\")
        val directFile = File(vaultDir, cleanName)
        if (directFile.exists()) return directFile

        val targetName = cleanName.lowercase()
        val targetSlug = cleanName.removeSuffix(".md").lowercase()

        return vaultDir.walkTopDown().firstOrNull { file ->
            file.isFile && (file.name.lowercase() == targetName || file.nameWithoutExtension.lowercase() == targetSlug)
        }
    }

    override suspend fun executeTool(toolCall: ToolCall): Result<Any> = withContext(Dispatchers.IO) {
        val vaultDir = AppPaths.vaultRoot(context)
        val entryPoint = EntryPointAccessors.fromApplication(context, MemoryPluginEntryPoint::class.java)

        try {
            when (toolCall.name) {
                TOOL_LIST_MEMORY -> {
                    val notes = entryPoint.vaultFileStore().listAllNotes()
                    val filesArray = JSONArray()
                    notes.forEach { note ->
                        val obj = JSONObject().apply {
                            put("name", note.title)
                            put("id", note.id)
                            put("folder", note.folder)
                            put("path", note.filePath)
                        }
                        filesArray.put(obj)
                    }
                    Result.success(filesArray)
                }
                TOOL_READ_MEMORY -> {
                    val name = toolCall.getString("name")
                    val file = findFileInVault(vaultDir, name)
                    if (file == null || !file.exists()) {
                        Result.success("Error: File '$name' not found in vault.")
                    } else {
                        val parsed = com.bit.util.DocumentParser.parseDocument(file, context)
                        if (parsed.isSuccess) {
                            Result.success(parsed.getOrThrow())
                        } else {
                            Result.success("Error reading file '$name': ${parsed.exceptionOrNull()?.message}")
                        }
                    }
                }
                TOOL_CREATE_MEMORY -> {
                    val name = toolCall.getString("name")
                    val content = toolCall.getString("content")

                    val note = MemoryNote(
                        title = name.removeSuffix(".md"),
                        content = content,
                        folder = "ai_memory",
                        noteType = "fact"
                    )

                    val savedNote = entryPoint.vaultFileStore().writeNote(note)
                    entryPoint.memoryNoteDao().insertNote(savedNote)
                    entryPoint.globalRagOrchestrator().reloadNoteIntoGraph(savedNote)

                    Result.success("Created memory file: ${savedNote.filePath}")
                }
                TOOL_EDIT_MEMORY -> {
                    val name = toolCall.getString("name")
                    val content = toolCall.getString("content", "")
                    val oldString = toolCall.getString("old_string", "")
                    val newString = toolCall.getString("new_string", "")

                    val file = findFileInVault(vaultDir, name)
                    if (file == null || !file.exists()) {
                        Result.success("Error: File '$name' not found in vault.")
                    } else {
                        val existingNote = entryPoint.vaultFileStore().readNote(file)
                        val updatedContent = when {
                            oldString.isNotEmpty() && newString.isNotEmpty() -> {
                                val currentText = existingNote?.content
                                    ?: if (file.extension.lowercase() == "md") String(file.readBytes(), Charsets.UTF_8)
                                    else return@withContext Result.success("Error: Cannot edit non-text file '${file.name}'.")
                                if (currentText.contains(oldString)) {
                                    currentText.replace(oldString, newString)
                                } else {
                                    return@withContext Result.success("Error: old_string not found in file.")
                                }
                            }
                            content.isNotEmpty() -> content
                            else -> return@withContext Result.success("Error: Provide either content or old_string/new_string pair.")
                        }

                        val noteToSave = (existingNote ?: MemoryNote(
                            title = file.nameWithoutExtension,
                            content = updatedContent,
                            filePath = file.absolutePath,
                            folder = file.parentFile?.name ?: "notes"
                        )).copy(content = updatedContent, updatedAt = System.currentTimeMillis())

                        val savedNote = entryPoint.vaultFileStore().writeNote(noteToSave)
                        entryPoint.memoryNoteDao().insertNote(savedNote)
                        entryPoint.globalRagOrchestrator().reloadNoteIntoGraph(savedNote)

                        Result.success("Updated file: ${savedNote.filePath}")
                    }
                }
                TOOL_DELETE_MEMORY -> {
                    val name = toolCall.getString("name")
                    val file = findFileInVault(vaultDir, name)
                    if (file != null && file.exists()) {
                        val note = entryPoint.vaultFileStore().readNote(file) ?: MemoryNote(
                            id = file.nameWithoutExtension,
                            title = file.nameWithoutExtension,
                            content = "",
                            filePath = file.absolutePath
                        )
                        entryPoint.vaultFileStore().deleteNote(note)
                        entryPoint.memoryNoteDao().deleteNoteById(note.id)
                        entryPoint.globalRagOrchestrator().removeNoteFromGraph(note.id)

                        Result.success("Deleted file: ${file.name}")
                    } else {
                        Result.success("Error: Could not find or delete file '$name'.")
                    }
                }
                else -> Result.failure(IllegalArgumentException("Unknown tool: ${toolCall.name}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tool error", e)
            Result.success("Error: ${e.message}")
        }
    }

    @Composable
    override fun ToolCallUI() {}

    @Composable
    override fun CacheToolUI(data: JSONObject) {
        val type = data.optString("type", "")
        if (type == "list_memory_files") {
            val files = data.optJSONArray("files")
            Column(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Memory Files",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (files != null) {
                    for (i in 0 until files.length()) {
                        Text(
                            text = "• ${files.getString(i)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            Text(
                text = data.toString(2),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
