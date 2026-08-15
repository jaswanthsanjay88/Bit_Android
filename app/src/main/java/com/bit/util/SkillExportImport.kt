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

    private fun yamlEscape(str: String): String {
        return if (str.contains(":") || str.contains("\n") || str.contains("\"") || str.contains("'")) {
            "\"" + str.replace("\"", "\\\"").replace("\n", "\\n") + "\""
        } else {
            str
        }
    }
}
