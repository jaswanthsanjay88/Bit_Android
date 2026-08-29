package com.bit.util

import android.content.Context
import android.net.Uri
import com.bit.models.Skill
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

/**
 * Utility for importing and exporting Claude Agent Skills.
 * Supports SKILL.md format (YAML frontmatter + markdown body) and native JSON format.
 */
object SkillExportImport {

    sealed class ImportResult {
        data class Success(val skill: Skill, val format: String) : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    /**
     * Export a skill to standard SKILL.md format (YAML frontmatter + markdown body).
     */
    fun exportToSkillMd(skill: Skill): String {
        return buildString {
            appendLine("---")
            appendLine("name: ${skill.name}")
            appendLine("description: ${yamlEscape(skill.description)}")
            if (!skill.icon.isNullOrBlank()) {
                appendLine("icon: ${skill.icon}")
            }
            appendLine("---")
            appendLine()
            append(skill.instructions)
        }
    }

    /**
     * Export a skill to native JSON format.
     */
    fun exportToJson(skill: Skill): String {
        val json = JSONObject().apply {
            put("version", 1)
            put("format", "bit_skill")
            put("skill", JSONObject().apply {
                put("id", skill.id)
                put("name", skill.name)
                put("description", skill.description)
                put("icon", skill.icon ?: "")
                put("instructions", skill.instructions)
                put("enabled", skill.enabled)
                put("isBuiltIn", skill.isBuiltIn)
                put("createdAt", skill.createdAt)
            })
        }
        return json.toString(2)
    }

    /**
     * Import a skill from string. Auto-detects format (SKILL.md or JSON).
     */
    fun importFromString(content: String): ImportResult {
        val trimmed = content.trim()

        // 1. Try JSON
        if (trimmed.startsWith("{")) {
            val jsonRes = tryImportJson(trimmed)
            if (jsonRes is ImportResult.Success) return jsonRes
        }

        // 2. Try SKILL.md (YAML frontmatter ---)
        if (trimmed.startsWith("---")) {
            val mdRes = tryImportSkillMd(trimmed)
            if (mdRes is ImportResult.Success) return mdRes
        }

        // Fallback attempts
        val fallbackJson = tryImportJson(trimmed)
        if (fallbackJson is ImportResult.Success) return fallbackJson

        val fallbackMd = tryImportSkillMd(trimmed)
        if (fallbackMd is ImportResult.Success) return fallbackMd

        // If neither, treat entire text as raw instructions
        return ImportResult.Success(
            Skill(
                name = "Imported Skill",
                description = "Custom imported prompt skill",
                instructions = trimmed,
                enabled = true,
                isBuiltIn = false
            ),
            format = "raw_text"
        )
    }

    /**
     * Import a skill from a content Uri.
     */
    fun importFromUri(context: Context, uri: Uri): ImportResult {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return ImportResult.Error("Could not open file")

            val content = inputStream.use { input ->
                BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            }

            importFromString(content)
        } catch (e: Exception) {
            ImportResult.Error("Failed to read file: ${e.message}")
        }
    }

    private fun tryImportJson(content: String): ImportResult {
        return try {
            val json = JSONObject(content)
            val skillObj = json.optJSONObject("skill") ?: json
            val name = skillObj.optString("name", "Imported Skill")
            val desc = skillObj.optString("description", "")
            val icon = skillObj.optString("icon").ifEmpty { null }
            val instructions = skillObj.optString("instructions", "")

            ImportResult.Success(
                Skill(
                    name = name,
                    description = desc,
                    icon = icon,
                    instructions = instructions,
                    enabled = true,
                    isBuiltIn = false
                ),
                format = "json"
            )
        } catch (e: Exception) {
            ImportResult.Error("Invalid JSON: ${e.message}")
        }
    }

    private fun tryImportSkillMd(content: String): ImportResult {
        val trimmed = content.trim()
        if (!trimmed.startsWith("---")) {
            return ImportResult.Error("Missing frontmatter delimiter (---)")
        }

        val closingIndex = trimmed.indexOf("---", startIndex = 3)
        if (closingIndex < 0) {
            return ImportResult.Error("Unclosed frontmatter (missing second ---)")
        }

        val frontmatter = trimmed.substring(3, closingIndex).trim()
        val body = trimmed.substring(closingIndex + 3).trim()

        val yamlMap = mutableMapOf<String, String>()
        for (line in frontmatter.lines()) {
            val colonIdx = line.indexOf(":")
            if (colonIdx > 0) {
                val key = line.substring(0, colonIdx).trim().lowercase()
                val value = line.substring(colonIdx + 1).trim().removeSurrounding("\"").removeSurrounding("'")
                yamlMap[key] = value
            }
        }

        val name = yamlMap["name"] ?: "Imported Skill"
        val desc = yamlMap["description"] ?: ""
        val icon = yamlMap["icon"]

        return ImportResult.Success(
            Skill(
                name = name,
                description = desc,
                icon = icon,
                instructions = body,
                enabled = true,
                isBuiltIn = false
            ),
            format = "skill_md"
        )
    }

    /**
     * Import a skill from an online URL (GitHub repository URL, raw GitHub URL, or direct SKILL.md/JSON link).
     */
    suspend fun importFromUrl(url: String): ImportResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) return@withContext ImportResult.Error("URL cannot be empty")

        // 1. Check if it's a GitHub URL
        val githubInfo = parseGitHubUrl(trimmedUrl)
        if (githubInfo != null) {
            return@withContext importFromGitHub(githubInfo)
        }

        // 2. Direct HTTP download
        try {
            val content = downloadText(trimmedUrl) ?: return@withContext ImportResult.Error("Could not fetch content from URL")
            importFromString(content)
        } catch (e: Exception) {
            ImportResult.Error("Failed to download skill: ${e.message}")
        }
    }

    private data class GitHubRepoInfo(
        val owner: String,
        val repo: String,
        val branch: String,
        val path: String
    )

    private fun parseGitHubUrl(url: String): GitHubRepoInfo? {
        val trimmed = url.trim().trimEnd('/')
        // https://github.com/owner/repo
        // https://github.com/owner/repo/tree/branch
        // https://github.com/owner/repo/tree/branch/sub/path
        val regex = Regex("""https://github\.com/([^/]+)/([^/]+)(?:/tree/([^/]+)(/.*)?)?""")
        val match = regex.matchEntire(trimmed) ?: return null
        val owner = match.groupValues[1]
        val repo = match.groupValues[2]
        val branch = match.groupValues[3].ifBlank { "main" }
        val subPath = match.groupValues[4].trimStart('/')
        return GitHubRepoInfo(owner, repo, branch, subPath)
    }

    private fun importFromGitHub(info: GitHubRepoInfo): ImportResult {
        // Try raw SKILL.md download first
        val rawPaths = listOf(
            if (info.path.isNotBlank()) "https://raw.githubusercontent.com/${info.owner}/${info.repo}/${info.branch}/${info.path}/SKILL.md" else null,
            if (info.path.isNotBlank()) "https://raw.githubusercontent.com/${info.owner}/${info.repo}/${info.branch}/${info.path}" else null,
            "https://raw.githubusercontent.com/${info.owner}/${info.repo}/${info.branch}/SKILL.md",
            "https://raw.githubusercontent.com/${info.owner}/${info.repo}/master/SKILL.md"
        ).filterNotNull()

        for (rawUrl in rawPaths) {
            val text = downloadText(rawUrl)
            if (!text.isNullOrBlank()) {
                val res = importFromString(text)
                if (res is ImportResult.Success) return res
            }
        }

        // Fallback: Use GitHub Contents API
        val apiUrl = if (info.path.isNotBlank()) {
            "https://api.github.com/repos/${info.owner}/${info.repo}/contents/${info.path}?ref=${info.branch}"
        } else {
            "https://api.github.com/repos/${info.owner}/${info.repo}/contents?ref=${info.branch}"
        }

        val jsonText = downloadText(apiUrl) ?: return ImportResult.Error("Could not fetch directory contents from GitHub repository")
        return try {
            val array = org.json.JSONArray(jsonText)
            var skillDownloadUrl: String? = null
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                val name = item.optString("name")
                if (name.equals("SKILL.md", ignoreCase = true) || name.endsWith(".md", ignoreCase = true)) {
                    skillDownloadUrl = item.optString("download_url")
                    if (name.equals("SKILL.md", ignoreCase = true)) break
                }
            }

            if (skillDownloadUrl != null) {
                val skillText = downloadText(skillDownloadUrl) ?: return ImportResult.Error("Could not download SKILL.md")
                importFromString(skillText)
            } else {
                ImportResult.Error("No SKILL.md found in ${info.owner}/${info.repo}/${info.path}")
            }
        } catch (e: Exception) {
            ImportResult.Error("Failed to parse GitHub repository response: ${e.message}")
        }
    }

    private fun downloadText(urlStr: String): String? {
        return try {
            val url = java.net.URL(urlStr)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "BIT-Android-Skills/1.0")
            conn.setRequestProperty("Accept", "application/vnd.github+json, text/plain, */*")
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun yamlEscape(str: String): String {
        return if (str.contains(":") || str.contains("\n") || str.contains("\"") || str.contains("'")) {
            "\"" + str.replace("\"", "\\\"").replace("\n", "\\n") + "\""
        } else {
            str
        }
    }
}
