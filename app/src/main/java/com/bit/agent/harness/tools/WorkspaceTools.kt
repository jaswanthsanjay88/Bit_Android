package com.bit.agent.harness.tools

import android.content.Context
import android.util.Log
import com.bit.agent.harness.model.ToolObservation
import com.bit.api.ToolDefinition
import com.bit.api.ToolFunction
import com.bit.api.ToolParameters
import com.bit.api.ToolProperty
import org.json.JSONObject
import java.io.File

private const val TAG = "WorkspaceTools"
private const val MAX_READ_BYTES = 512 * 1024 // 512 KB limit

/**
 * Workspace File Read Tool.
 */
class WorkspaceReadFileTool(private val context: Context) : AgentTool {
    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = "workspace_read_file",
            description = "Read a file from the workspace or local storage. Supports UTF-8 text files.",
            parameters = ToolParameters(
                properties = mapOf(
                    "path" to ToolProperty(type = "string", description = "Relative or absolute path to the file"),
                    "start_line" to ToolProperty(type = "integer", description = "Optional starting line number (1-indexed)"),
                    "end_line" to ToolProperty(type = "integer", description = "Optional ending line number (inclusive)")
                ),
                required = listOf("path")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = JSONObject(argumentsJson)
            val path = args.optString("path", "").trim()
            val startLine = args.optInt("start_line", 1).coerceAtLeast(1)
            val endLine = args.optInt("end_line", -1)

            val file = resolveFile(context, path)
            if (!file.exists()) {
                return ToolObservation.error(
                    summary = "File not found: $path",
                    recoveryHint = "Verify file path using dir_list or create it using workspace_write_file."
                )
            }

            val lines = file.readLines()
            val totalLines = lines.size
            val effectiveEnd = if (endLine in startLine..totalLines) endLine else totalLines
            val sliced = lines.subList(startLine - 1, effectiveEnd)
            val content = sliced.joinToString("\n")

            ToolObservation.success(
                summary = "Read ${sliced.size}/$totalLines lines from ${file.name}",
                payload = content,
                artifacts = listOf(file.absolutePath),
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "workspace_read_file error: ${e.message}", e)
            ToolObservation.error(
                summary = "Failed to read file: ${e.message}",
                recoveryHint = "Check if file is accessible and encoded in UTF-8.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}

/**
 * Workspace File Write Tool.
 */
class WorkspaceWriteFileTool(private val context: Context) : AgentTool {
    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = "workspace_write_file",
            description = "Write text content to a file in the workspace. Creates parent directories automatically.",
            parameters = ToolParameters(
                properties = mapOf(
                    "path" to ToolProperty(type = "string", description = "Target file path"),
                    "content" to ToolProperty(type = "string", description = "File content to write"),
                    "overwrite" to ToolProperty(type = "boolean", description = "Whether to overwrite if file exists (default: true)")
                ),
                required = listOf("path", "content")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = JSONObject(argumentsJson)
            val path = args.optString("path", "").trim()
            val content = args.optString("content", "")
            val overwrite = args.optBoolean("overwrite", true)

            val file = resolveFile(context, path)
            if (file.exists() && !overwrite) {
                return ToolObservation.warning(
                    summary = "File exists and overwrite is false: $path",
                    recoveryHint = "Set overwrite=true or choose a different filename."
                )
            }

            file.parentFile?.mkdirs()
            file.writeText(content)

            ToolObservation.success(
                summary = "Wrote ${content.length} characters to ${file.name}",
                payload = "File saved successfully at ${file.absolutePath}",
                artifacts = listOf(file.absolutePath),
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "workspace_write_file error: ${e.message}", e)
            ToolObservation.error(
                summary = "Failed to write file: ${e.message}",
                recoveryHint = "Check storage permissions and valid directory paths.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}

/**
 * Workspace File Edit / Patch Tool.
 */
class WorkspaceEditFileTool(private val context: Context) : AgentTool {
    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = "workspace_edit_file",
            description = "Edit an existing file by replacing an exact target chunk of text with replacement content.",
            parameters = ToolParameters(
                properties = mapOf(
                    "path" to ToolProperty(type = "string", description = "Path to the file to edit"),
                    "target_chunk" to ToolProperty(type = "string", description = "Exact string chunk to search and replace"),
                    "replacement_chunk" to ToolProperty(type = "string", description = "New content to insert in place of target_chunk")
                ),
                required = listOf("path", "target_chunk", "replacement_chunk")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = JSONObject(argumentsJson)
            val path = args.optString("path", "").trim()
            val targetChunk = args.optString("target_chunk", "")
            val replacementChunk = args.optString("replacement_chunk", "")

            val file = resolveFile(context, path)
            if (!file.exists()) {
                return ToolObservation.error("File not found: $path", "Verify file exists before editing.")
            }

            val text = file.readText()
            if (!text.contains(targetChunk)) {
                return ToolObservation.error(
                    summary = "target_chunk was not found in ${file.name}",
                    recoveryHint = "Read the latest file content using workspace_read_file and ensure target_chunk matches exactly."
                )
            }

            val updated = text.replaceFirst(targetChunk, replacementChunk)
            file.writeText(updated)

            ToolObservation.success(
                summary = "Successfully edited ${file.name}",
                payload = "Replaced chunk in ${file.absolutePath}",
                artifacts = listOf(file.absolutePath),
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "workspace_edit_file error: ${e.message}", e)
            ToolObservation.error(
                summary = "Failed to edit file: ${e.message}",
                recoveryHint = "Ensure target_chunk exists and file is writeable.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}

/**
 * Workspace Shell Execution Tool.
 */
class WorkspaceShellTool(private val context: Context) : AgentTool {
    override val requiresApproval: Boolean = true

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = "workspace_shell",
            description = "Execute a shell command inside the workspace environment. Requires user authorization.",
            parameters = ToolParameters(
                properties = mapOf(
                    "command" to ToolProperty(type = "string", description = "The shell command line to execute")
                ),
                required = listOf("command")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = JSONObject(argumentsJson)
            val command = args.optString("command", "").trim()
            if (command.isEmpty()) {
                return ToolObservation.error("Command cannot be empty", "Provide a valid shell command.")
            }

            val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
            val process = ProcessBuilder("sh", "-c", command)
                .directory(baseDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                ToolObservation.success(
                    summary = "Command '$command' completed with code 0",
                    payload = output.take(8000),
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            } else {
                ToolObservation.warning(
                    summary = "Command '$command' exited with code $exitCode",
                    payload = output.take(8000),
                    recoveryHint = "Review error output and adjust command.",
                    executionTimeMs = System.currentTimeMillis() - startTime
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "workspace_shell error: ${e.message}", e)
            ToolObservation.error(
                summary = "Shell execution failed: ${e.message}",
                recoveryHint = "Ensure command syntax is valid for sh.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}

private fun resolveFile(context: Context?, path: String): File {
    val f = File(path)
    if (f.isAbsolute) return f
    val base = context?.getExternalFilesDir("workspace")
        ?: context?.let { File(it.filesDir, "workspace") }
        ?: File("./workspace")
    base.mkdirs()
    return File(base, path)
}

/**
 * File system write intent for optimistic concurrency control (DeepSeek Harness paradigm).
 */
sealed class FsWriteIntent {
    data object CreateIfAbsent : FsWriteIntent()
    data class ReplaceIfVersion(val expectedVersion: String) : FsWriteIntent()
    data object Unconditional : FsWriteIntent()
}

fun computeFileVersion(file: File): String {
    if (!file.exists()) return "absent"
    return "${file.lastModified()}_${file.length()}"
}

/**
 * DeepSeek Harness StrReplaceEditor Tool.
 * Unified file tool supporting 'view', 'create', 'str_replace', and 'insert' with optimistic version checking.
 */
class StrReplaceEditorTool(private val context: Context? = null) : AgentTool {
    companion object {
        const val NAME = "str_replace_editor"
        private const val MAX_OUTPUT_CHARS = 16_000
        private const val TRUNCATED_MESSAGE = "\n<response clipped><NOTE>To save on context only part of this file has been shown to you.</NOTE>"
    }

    override val promptOrder: Int = 110
    override val systemPromptContribution: String = """
        Use 'str_replace_editor' for viewing and modifying files.
        - 'view': inspect file content with line numbers or list directory up to 2 levels.
        - 'create': write new files (fails if file already exists).
        - 'str_replace': replace unique verbatim old_str with new_str.
        - 'insert': insert new_str after insert_line.
    """.trimIndent()

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = NAME,
            description = """
                Custom editing tool for viewing, creating and editing files.
                * If path is a file, 'view' displays the content with line numbers. If path is a directory, 'view' lists non-hidden files and directories up to 2 levels deep.
                * 'create' creates a new file with file_text (cannot overwrite existing files).
                * 'str_replace' replaces an exact, unique old_str with new_str.
                * 'insert' inserts new_str after line insert_line.
            """.trimIndent(),
            parameters = ToolParameters(
                properties = mapOf(
                    "command" to ToolProperty(
                        type = "string",
                        description = "The command to run: 'view', 'create', 'str_replace', or 'insert'"
                    ),
                    "path" to ToolProperty(type = "string", description = "Path to file or directory"),
                    "file_text" to ToolProperty(type = "string", description = "Content of the file for 'create'"),
                    "old_str" to ToolProperty(type = "string", description = "Exact string to replace for 'str_replace'"),
                    "new_str" to ToolProperty(type = "string", description = "Replacement or inserted string"),
                    "insert_line" to ToolProperty(type = "integer", description = "Line number after which to insert for 'insert'"),
                    "expected_version" to ToolProperty(type = "string", description = "Optional version token from previous 'view' to ensure no concurrent modification"),
                    "view_range" to ToolProperty(type = "array", description = "Optional [start_line, end_line] for 'view'")
                ),
                required = listOf("command", "path")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = JSONObject(argumentsJson)
            val command = args.optString("command", "").trim()
            val path = args.optString("path", "").trim()
            if (path.isEmpty()) {
                return ToolObservation.error("Path cannot be empty", "Provide a valid file or directory path.")
            }

            val target = resolveFile(context, path)

            when (command) {
                "view" -> handleView(target, args, startTime)
                "create" -> handleCreate(target, args, startTime)
                "str_replace" -> handleStrReplace(target, args, startTime)
                "insert" -> handleInsert(target, args, startTime)
                else -> ToolObservation.error(
                    summary = "Unknown command: '$command'",
                    recoveryHint = "Allowed commands are 'view', 'create', 'str_replace', 'insert'."
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "str_replace_editor error: ${e.message}", e)
            ToolObservation.error(
                summary = "str_replace_editor error: ${e.message}",
                recoveryHint = "Review arguments and ensure file permissions are valid.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun handleView(target: File, args: JSONObject, startTime: Long): ToolObservation {
        if (!target.exists()) {
            return ToolObservation.error("Path does not exist: ${target.path}", "Check path or create the file.")
        }
        val version = computeFileVersion(target)
        if (target.isDirectory) {
            val rows = mutableListOf<String>()
            target.walkTopDown().maxDepth(2).filter { file ->
                val n = file.name
                !n.startsWith(".") && n != "node_modules" && n != "__pycache__" && n != "build"
            }.forEach { f ->
                val type = if (f.isDirectory) "d" else "f"
                rows.add("$type\t${f.path}")
            }
            val text = rows.sorted().joinToString("\n")
            val output = if (text.length > MAX_OUTPUT_CHARS) text.take(MAX_OUTPUT_CHARS) + TRUNCATED_MESSAGE else text
            return ToolObservation.success(
                summary = "Listed directory: ${target.name} (${rows.size} items) [version=$version]",
                payload = output,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }

        val lines = target.readLines()
        var startLine = 1
        var endLine = lines.size

        val rangeArr = args.optJSONArray("view_range")
        if (rangeArr != null && rangeArr.length() >= 2) {
            val reqStart = rangeArr.optInt(0, 1)
            val reqEnd = rangeArr.optInt(1, -1)
            startLine = reqStart.coerceIn(1, lines.size.coerceAtLeast(1))
            endLine = if (reqEnd == -1 || reqEnd > lines.size) lines.size else reqEnd.coerceAtLeast(startLine)
        }

        val numbered = lines.mapIndexedNotNull { idx, line ->
            val lineNum = idx + 1
            if (lineNum in startLine..endLine) {
                String.format("%6d  %s", lineNum, line)
            } else null
        }.joinToString("\n")

        val output = if (numbered.length > MAX_OUTPUT_CHARS) numbered.take(MAX_OUTPUT_CHARS) + TRUNCATED_MESSAGE else numbered
        return ToolObservation.success(
            summary = "Viewed ${target.name} (lines $startLine..$endLine of ${lines.size}) [version=$version]",
            payload = output,
            artifacts = listOf(target.absolutePath),
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }

    private fun handleCreate(target: File, args: JSONObject, startTime: Long): ToolObservation {
        if (target.exists()) {
            return ToolObservation.error(
                summary = "File already exists at: ${target.path}. Cannot overwrite files using command 'create'.",
                recoveryHint = "Use 'str_replace' to edit existing files or choose a new path."
            )
        }
        val fileText = args.optString("file_text", "")
        target.parentFile?.mkdirs()
        target.writeText(fileText)
        val newVersion = computeFileVersion(target)
        return ToolObservation.success(
            summary = "Created new file: ${target.name} (${fileText.length} chars) [version=$newVersion]",
            payload = "New file created successfully at: ${target.absolutePath}",
            artifacts = listOf(target.absolutePath),
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }

    private fun handleStrReplace(target: File, args: JSONObject, startTime: Long): ToolObservation {
        if (!target.exists()) {
            return ToolObservation.error("File does not exist: ${target.path}", "Use 'create' to create a new file.")
        }
        val expectedVersion = args.optString("expected_version", "").trim()
        val currentVersion = computeFileVersion(target)
        if (expectedVersion.isNotEmpty() && expectedVersion != currentVersion) {
            return ToolObservation.error(
                summary = "Version conflict on ${target.name}: file was modified (expected $expectedVersion, current $currentVersion).",
                recoveryHint = "View the file again to refresh lines and version before applying edits."
            )
        }

        val oldStr = args.optString("old_str", "")
        if (oldStr.isEmpty()) {
            return ToolObservation.error("Parameter 'old_str' cannot be empty for 'str_replace'.", "Provide exact text to replace.")
        }
        val newStr = args.optString("new_str", "")
        val content = target.readText()

        val occurrences = mutableListOf<Int>()
        var index = content.indexOf(oldStr)
        while (index >= 0) {
            occurrences.add(index)
            index = content.indexOf(oldStr, index + oldStr.length)
        }

        if (occurrences.isEmpty()) {
            return ToolObservation.error(
                summary = "No replacement was performed, old_str did not appear verbatim in ${target.name}.",
                recoveryHint = "View the file to inspect the exact lines and whitespace, then retry."
            )
        }

        if (occurrences.size > 1) {
            val lines = content.split("\n")
            val matchingLines = mutableListOf<Int>()
            for (occ in occurrences) {
                val lineNum = content.substring(0, occ).count { it == '\n' } + 1
                matchingLines.add(lineNum)
            }
            return ToolObservation.error(
                summary = "No replacement was performed. Multiple occurrences of old_str in lines [${matchingLines.joinToString()}]. Please ensure it is unique.",
                recoveryHint = "Include more surrounding context lines in old_str to make it unique."
            )
        }

        val updated = content.substring(0, occurrences[0]) + newStr + content.substring(occurrences[0] + oldStr.length)
        target.writeText(updated)
        val newVersion = computeFileVersion(target)
        return ToolObservation.success(
            summary = "The file ${target.name} has been edited successfully [version=$newVersion].",
            payload = "Replaced unique block in ${target.absolutePath}",
            artifacts = listOf(target.absolutePath),
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }

    private fun handleInsert(target: File, args: JSONObject, startTime: Long): ToolObservation {
        if (!target.exists()) {
            return ToolObservation.error("File does not exist: ${target.path}", "Verify path before inserting.")
        }
        val expectedVersion = args.optString("expected_version", "").trim()
        val currentVersion = computeFileVersion(target)
        if (expectedVersion.isNotEmpty() && expectedVersion != currentVersion) {
            return ToolObservation.error(
                summary = "Version conflict on ${target.name}: file was modified (expected $expectedVersion, current $currentVersion).",
                recoveryHint = "View the file again to refresh lines and version before inserting."
            )
        }

        val insertLine = args.optInt("insert_line", -1)
        val newStr = args.optString("new_str", "")
        val lines = target.readLines().toMutableList()

        if (insertLine < 0 || insertLine > lines.size) {
            return ToolObservation.error(
                summary = "Invalid insert_line: $insertLine. Must be in [0, ${lines.size}]",
                recoveryHint = "Provide a valid line number within the file."
            )
        }

        val insertLines = newStr.split("\n")
        lines.addAll(insertLine, insertLines)
        target.writeText(lines.joinToString("\n"))
        val newVersion = computeFileVersion(target)

        return ToolObservation.success(
            summary = "Inserted ${insertLines.size} lines after line $insertLine in ${target.name} [version=$newVersion]",
            payload = "File updated at ${target.absolutePath}",
            artifacts = listOf(target.absolutePath),
            executionTimeMs = System.currentTimeMillis() - startTime
        )
    }
}

/**
 * DeepSeek Harness Todo Task List Tool.
 * Provides whole-list replacement for structured multi-step planning and progress tracking.
 */
class TodoWriteTool(
    private val allowParallelInProgress: Boolean = false
) : AgentTool {
    companion object {
        const val NAME = "todo_write"
    }

    override val promptOrder: Int = 105
    override val systemPromptContribution: String = """
        Use 'todo_write' to record and update multi-step plans. Send the full list each time to reflect current progress.
    """.trimIndent()

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = NAME,
            description = """
                Record and update a structured task list for the current work. Send the ENTIRE list every call — it REPLACES the previous list.
                Statuses: 'pending' (not started), 'in_progress' (active now), 'completed' (done).
            """.trimIndent(),
            parameters = ToolParameters(
                properties = mapOf(
                    "todos" to ToolProperty(
                        type = "array",
                        description = "The COMPLETE task list of { content: string, status: 'pending'|'in_progress'|'completed' }"
                    )
                ),
                required = listOf("todos")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = JSONObject(argumentsJson)
            val todosArr = args.optJSONArray("todos")
                ?: return ToolObservation.error("Missing 'todos' array", "Provide a list of tasks.")

            val seen = mutableSetOf<String>()
            var inProgressCount = 0
            var pendingCount = 0
            var completedCount = 0

            val list = mutableListOf<Map<String, String>>()

            for (i in 0 until todosArr.length()) {
                val item = todosArr.optJSONObject(i) ?: continue
                val content = item.optString("content", "").trim()
                val status = item.optString("status", "pending").trim().lowercase()

                if (content.isEmpty()) {
                    return ToolObservation.error("Invalid todo: 'content' must be non-empty", "Provide non-empty task description.")
                }
                if (content in seen) {
                    return ToolObservation.error("Duplicate task content: '$content'", "Ensure task descriptions are unique.")
                }
                seen.add(content)

                when (status) {
                    "in_progress" -> inProgressCount++
                    "completed" -> completedCount++
                    "pending" -> pendingCount++
                    else -> return ToolObservation.error("Invalid status '$status'", "Status must be pending, in_progress, or completed.")
                }
                list.add(mapOf("content" to content, "status" to status))
            }

            if (!allowParallelInProgress && inProgressCount > 1) {
                return ToolObservation.error(
                    summary = "At most one task may be 'in_progress' (found $inProgressCount).",
                    recoveryHint = "Mark only the currently active task as in_progress."
                )
            }

            val summary = "Updated todo list: $pendingCount pending, $inProgressCount in progress, $completedCount completed."
            val payload = JSONObject().apply {
                put("pending", pendingCount)
                put("in_progress", inProgressCount)
                put("completed", completedCount)
                put("todos", org.json.JSONArray(list.map { JSONObject(it) }))
            }.toString()

            ToolObservation.success(
                summary = summary,
                payload = payload,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "todo_write error: ${e.message}", e)
            ToolObservation.error(
                summary = "Failed to update todo list: ${e.message}",
                recoveryHint = "Check JSON format for todos parameter.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }
}
