package com.bit.plugins

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import com.bit.models.plugins.PluginInfo
import com.bit.plugins.api.SuperPlugin
import com.bit.repo.WorkspaceRepository
import com.dark.gguf_lib.toolcalling.ToolCall
import com.dark.gguf_lib.toolcalling.ToolDefinitionBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.workspace.WorkspaceManager
import me.rerere.workspace.WorkspaceStorageArea
import org.json.JSONObject
import java.util.Locale

class WorkspacePlugin(
    private val context: Context,
    private val workspaceRepository: WorkspaceRepository,
) : SuperPlugin {

    companion object {
        private const val TAG = "WorkspacePlugin"
        const val PLUGIN_NAME = "Linux Workspace"
        const val TOOL_WORKSPACE_SHELL = "workspace_shell"
        const val TOOL_WORKSPACE_READ_FILE = "workspace_read_file"
        const val TOOL_WORKSPACE_WRITE_FILE = "workspace_write_file"
        const val TOOL_WORKSPACE_EDIT_FILE = "workspace_edit_file"
    }

    override fun getPluginInfo(): PluginInfo {
        val shellBuilder = ToolDefinitionBuilder(
            TOOL_WORKSPACE_SHELL,
            "Execute a shell command inside the on-device Linux PRoot workspace sandbox (e.g. 'python3 script.py', 'gcc -O3 main.c', 'ls -la', 'apk add package'). Use this to run code and terminal commands."
        )
            .stringParam("command", "The shell command to execute inside Linux sandbox (e.g. 'python3 test.py', 'cat file.txt')", true)
            .stringParam("cwd", "Optional working directory relative to /workspace (defaults to /workspace)", false)
            .numberParam("timeout", "Command timeout in seconds (default: 30)", false)
            .stringParam("workspace_id", "Optional workspace ID", false)

        val readFileBuilder = ToolDefinitionBuilder(
            TOOL_WORKSPACE_READ_FILE,
            "Read source code, scripts, or project files from the Linux project workspace (/workspace) or Linux filesystem."
        )
            .stringParam("path", "Code or file path to read (e.g. '/workspace/main.py', 'script.sh')", true)
            .stringParam("workspace_id", "Optional workspace ID", false)

        val writeFileBuilder = ToolDefinitionBuilder(
            TOOL_WORKSPACE_WRITE_FILE,
            "Create or write source code, scripts (Python, C, Bash), or project files inside the Linux workspace /workspace directory. Use this when generating code to execute or compile."
        )
            .stringParam("path", "Project file path to write (e.g. 'main.py', 'app.c', 'script.sh')", true)
            .stringParam("text", "The code or script text content to write", true)
            .booleanParam("overwrite", "Whether to overwrite existing file (default: true)", false)
            .stringParam("workspace_id", "Optional workspace ID", false)

        val editFileBuilder = ToolDefinitionBuilder(
            TOOL_WORKSPACE_EDIT_FILE,
            "Perform an exact code replacement in an existing workspace code file."
        )
            .stringParam("path", "File path to edit (e.g. 'main.py')", true)
            .stringParam("old_text", "Exact substring or code block to replace", true)
            .stringParam("new_text", "Replacement code string", true)
            .booleanParam("replace_all", "Whether to replace all occurrences (default: false)", false)
            .stringParam("workspace_id", "Optional workspace ID", false)

        return PluginInfo(
            name = PLUGIN_NAME,
            description = "On-device Linux PRoot workspace for running code, compiling, executing shell commands, and managing development project files. Do NOT use for personal user memories or notes.",
            author = "BIT Workspace Engine",
            version = "1.0.0",
            toolDefinitionBuilder = listOf(shellBuilder, readFileBuilder, writeFileBuilder, editFileBuilder)
        )
    }

    override fun serializeResult(data: Any): String {
        return when (data) {
            is JSONObject -> data.toString()
            is String -> data
            else -> data.toString()
        }
    }

    override suspend fun executeTool(toolCall: ToolCall): Result<Any> = withContext(Dispatchers.IO) {
        val toolName = toolCall.name.lowercase(Locale.ROOT)
        Log.i(TAG, "Executing Workspace tool: $toolName with arguments: ${toolCall.arguments}")

        try {
            val args = toolCall.arguments
            val workspace = resolveWorkspace(args.optString("workspace_id", ""))
                ?: return@withContext Result.success(
                    JSONObject().apply {
                        put("status", "error")
                        put("message", "No active Linux workspace found. Create a workspace in Settings -> Linux Workspaces first.")
                    }
                )

            val resultObj = when (toolName) {
                TOOL_WORKSPACE_SHELL -> {
                    val command = args.optString("command", "").trim()
                    require(command.isNotBlank()) { "command is required" }
                    val cwd = args.optString("cwd", "").removePrefix("/workspace/").removePrefix("/workspace").trim()
                    val timeoutSec = args.optLong("timeout", 30L).coerceIn(1L, 600L)

                    val processId = com.bit.workspace.WorkspaceProcessManager.registerAiCommand(workspace.id, command)
                    val result = workspaceRepository.executeCommand(
                        id = workspace.id,
                        command = command,
                        cwd = cwd,
                        timeoutMillis = timeoutSec * 1000L,
                    )

                    com.bit.workspace.WorkspaceProcessManager.finishAiCommand(
                        processId = processId,
                        exitCode = result.exitCode,
                        output = (result.stdout + if (result.stderr.isNotBlank()) "\n${result.stderr}" else "").trim(),
                        timedOut = result.timedOut
                    )

                    JSONObject().apply {
                        put("status", if (result.exitCode == 0) "success" else "error")
                        put("exitCode", result.exitCode)
                        put("stdout", result.stdout)
                        put("stderr", result.stderr)
                        put("timedOut", result.timedOut)
                        if (result.truncated) put("truncated", true)
                    }
                }

                TOOL_WORKSPACE_READ_FILE -> {
                    val rawPath = args.optString("path", "").trim()
                    require(rawPath.isNotBlank()) { "path is required" }
                    val path = rawPath.removePrefix("/workspace/").removePrefix("/workspace").trimStart('/')
                    val area = if (rawPath.startsWith("/") && !rawPath.startsWith("/workspace")) {
                        WorkspaceStorageArea.LINUX
                    } else {
                        WorkspaceStorageArea.FILES
                    }

                    val content = workspaceRepository.readText(workspace.id, path, area)
                    JSONObject().apply {
                        put("status", "success")
                        put("path", rawPath)
                        put("content", content)
                    }
                }

                TOOL_WORKSPACE_WRITE_FILE -> {
                    val rawPath = args.optString("path", "").trim()
                    require(rawPath.isNotBlank()) { "path is required" }
                    val text = args.optString("text", "")
                    val overwrite = args.optBoolean("overwrite", true)
                    val path = rawPath.removePrefix("/workspace/").removePrefix("/workspace").trimStart('/')

                    val entry = workspaceRepository.writeText(workspace.id, path, text, overwrite)
                    JSONObject().apply {
                        put("status", "success")
                        put("path", "/workspace/${entry.path}")
                        put("sizeBytes", entry.sizeBytes)
                        put("updatedAt", entry.updatedAt)
                    }
                }

                TOOL_WORKSPACE_EDIT_FILE -> {
                    val rawPath = args.optString("path", "").trim()
                    require(rawPath.isNotBlank()) { "path is required" }
                    val oldText = args.optString("old_text", "")
                    val newText = args.optString("new_text", "")
                    val replaceAll = args.optBoolean("replace_all", false)
                    require(oldText.isNotEmpty()) { "old_text must not be empty" }

                    val path = rawPath.removePrefix("/workspace/").removePrefix("/workspace").trimStart('/')
                    val current = workspaceRepository.readText(workspace.id, path, WorkspaceStorageArea.FILES)
                    require(current.contains(oldText)) { "old_text was not found in $rawPath" }

                    val updated = if (replaceAll) current.replace(oldText, newText) else current.replaceFirst(oldText, newText)
                    val entry = workspaceRepository.writeText(workspace.id, path, updated, overwrite = true)

                    JSONObject().apply {
                        put("status", "success")
                        put("path", "/workspace/${entry.path}")
                        put("message", "File edited successfully")
                    }
                }

                else -> {
                    JSONObject().apply {
                        put("status", "unknown_tool")
                        put("tool", toolName)
                    }
                }
            }

            Result.success(resultObj)
        } catch (e: Exception) {
            Log.e(TAG, "Error executing workspace tool", e)
            val errorObj = JSONObject().apply {
                put("status", "error")
                put("message", e.message ?: "Failed to execute workspace tool")
            }
            Result.success(errorObj)
        }
    }

    private suspend fun resolveWorkspace(explicitId: String): com.bit.models.table_schema.WorkspaceEntity? {
        if (explicitId.isNotBlank()) {
            val found = workspaceRepository.getById(explicitId)
            if (found != null) return found
        }
        val all = workspaceRepository.getAll()
        if (all.isNotEmpty()) {
            return all.maxByOrNull { it.updatedAt }
        }
        return try {
            workspaceRepository.create("Main Workspace")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-create default workspace", e)
            null
        }
    }

    @Composable
    override fun ToolCallUI() {
        // Handled in chat UI
    }

    @Composable
    override fun CacheToolUI(data: JSONObject) {
        // Handled in chat cache UI
    }
}
