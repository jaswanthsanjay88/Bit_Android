package com.bit.plugins

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import com.bit.models.plugins.PluginInfo
import com.bit.plugins.api.SuperPlugin
import com.bit.skills.SkillManager
import com.dark.gguf_lib.toolcalling.ToolCall
import com.dark.gguf_lib.toolcalling.ToolDefinitionBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Agent Skills SuperPlugin implementing Anthropic's Progressive Disclosure architecture.
 *
 * Instead of flooding the initial context window with complete instruction bodies,
 * skills are exposed as a lightweight catalog. When a user's task demands a skill,
 * the model dynamically invokes `manage_skills` (or `activate_skill` / `load_skill`)
 * to load and disclose the full instructions for that turn.
 */
class SkillPlugin(
    private val context: Context,
    private val skillManager: SkillManager
) : SuperPlugin {

    companion object {
        private const val TAG = "SkillPlugin"
        const val PLUGIN_NAME = "Agent Skills"
        const val TOOL_MANAGE_SKILLS = "manage_skills"
        const val TOOL_LOAD_SKILL = "load_skill"
        const val TOOL_ACTIVATE_SKILL = "activate_skill"
    }

    override fun getPluginInfo(): PluginInfo {
        val manageSkillsBuilder = ToolDefinitionBuilder(
            TOOL_MANAGE_SKILLS,
            "Activate available specialized agent skills into the context. Use this tool only when the user request clearly requires one of the available skill specializations."
        )
            .stringParam("skill", "The name or ID of the skill to activate (e.g. 'Web Search & Scraping', 'File Operations')", false)
            .stringParam("skills", "Comma-separated names of multiple skills to activate", false)

        val loadSkillBuilder = ToolDefinitionBuilder(
            TOOL_LOAD_SKILL,
            "Load full SKILL.md instructions for a specific skill into the context on demand."
        ).stringParam("skill_name", "Exact name of the skill to load", true)

        val activateSkillBuilder = ToolDefinitionBuilder(
            TOOL_ACTIVATE_SKILL,
            "Activate an agent skill specialization for the current turn."
        ).stringParam("skill_name", "Exact name of the skill to activate", true)

        return PluginInfo(
            name = PLUGIN_NAME,
            description = "Progressive disclosure engine for Anthropic Claude Agent Skills",
            author = "Anthropic Agent Skills Standard",
            version = "2.0.0",
            toolDefinitionBuilder = listOf(manageSkillsBuilder, loadSkillBuilder, activateSkillBuilder)
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
        Log.i(TAG, "Executing Skill progressive disclosure tool: $toolName with arguments: ${toolCall.arguments}")

        try {
            val args = toolCall.arguments
            val requestedNames = mutableListOf<String>()

            // Extract skill name(s) from various argument aliases
            val single = args.optString("skill", "").ifBlank {
                args.optString("skill_name", "").ifBlank {
                    args.optString("name", "")
                }
            }.trim()

            if (single.isNotBlank()) {
                requestedNames.add(single)
            }

            val multiple = args.optString("skills", "").trim()
            if (multiple.isNotBlank()) {
                multiple.split(",").forEach { s ->
                    val t = s.trim()
                    if (t.isNotBlank()) requestedNames.add(t)
                }
            }

            val skillsJsonArr = args.optJSONArray("skills")
            if (skillsJsonArr != null) {
                for (i in 0 until skillsJsonArr.length()) {
                    val s = skillsJsonArr.optString(i, "").trim()
                    if (s.isNotBlank()) requestedNames.add(s)
                }
            }

            val allSkills = skillManager.skills.value.filter { it.enabled }
            val activatedSkills = mutableListOf<com.bit.models.Skill>()
            val notFound = mutableListOf<String>()

            for (req in requestedNames) {
                val normalizedReq = req.lowercase(Locale.ROOT)
                val match = allSkills.find {
                    it.name.lowercase(Locale.ROOT) == normalizedReq ||
                    it.id.lowercase(Locale.ROOT) == normalizedReq ||
                    it.name.lowercase(Locale.ROOT).contains(normalizedReq)
                }
                if (match != null) {
                    if (!activatedSkills.contains(match)) {
                        activatedSkills.add(match)
                    }
                } else {
                    notFound.add(req)
                }
            }

            if (activatedSkills.isEmpty() && requestedNames.isNotEmpty()) {
                val availableList = allSkills.map { it.name }
                val errorResult = JSONObject().apply {
                    put("status", "not_found")
                    put("message", "No matching enabled skills found for: ${requestedNames.joinToString(", ")}")
                    put("availableSkills", JSONArray(availableList))
                }
                return@withContext Result.success(errorResult)
            }

            val responseObj = JSONObject().apply {
                put("status", "success")
                val activatedArr = JSONArray()
                activatedSkills.forEach { s ->
                    activatedArr.put(JSONObject().apply {
                        put("name", s.name)
                        put("description", s.description)
                        put("instructions", s.instructions)
                    })
                }
                put("activatedSkills", activatedArr)
                put("message", "Loaded ${activatedSkills.size} skill(s) into context. Follow the provided instructions precisely.")
            }

            Result.success(responseObj)
        } catch (e: Exception) {
            Log.e(TAG, "Error activating skills", e)
            Result.failure(e)
        }
    }

    @Composable
    override fun ToolCallUI() {
        // Rendered in chat UI
    }

    @Composable
    override fun CacheToolUI(data: JSONObject) {
        // Rendered in chat cache UI
    }
}
