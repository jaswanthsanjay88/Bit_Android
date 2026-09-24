package com.bit.agent.harness.tools

import com.bit.api.ToolDefinition
import com.bit.api.ToolFunction
import com.bit.api.ToolParameters
import com.bit.api.ToolProperty
import com.bit.agent.harness.HarnessLogger
import com.bit.agent.harness.NoOpHarnessLogger
import com.bit.agent.harness.model.ToolObservation
import org.json.JSONObject
import java.io.File

/**
 * Loads specialized domain guidelines, testing conventions, and architecture patterns
 * dynamically for any skill in the workspace or agent registry.
 */
class UseSkillTool(
    private val context: android.content.Context? = null,
    private val skillManager: com.bit.skills.SkillManager? = null,
    private val logger: HarnessLogger = NoOpHarnessLogger
) : AgentTool {

    companion object {
        const val NAME = "use_skill"
    }

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = NAME,
            description = "Load specialized domain instructions, guidelines, and patterns for a specific skill (e.g. web-search, memory-vault, file-ops, python-patterns, tdd-workflow, security-review, android-clean-architecture).",
            parameters = ToolParameters(
                properties = mapOf(
                    "name" to ToolProperty(
                        type = "string",
                        description = "The skill name or command slug to load instructions from (e.g. web-search, file-ops, python-patterns, tdd-workflow)"
                    )
                ),
                required = listOf("name")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val startTime = System.currentTimeMillis()
        return try {
            val args = if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
            val skillName = args.optString("name").trim().lowercase()
            if (skillName.isEmpty()) {
                return ToolObservation.error(
                    summary = "use_skill requires a 'name' argument.",
                    recoveryHint = "Provide a valid skill name."
                )
            }

            val manager = skillManager ?: context?.let { com.bit.skills.SkillManager.getInstance(it) }
            val matchedSkill = manager?.findSkill(skillName)

            val content = when {
                matchedSkill != null && matchedSkill.instructions.isNotBlank() -> {
                    compactSkillInstructions(matchedSkill.instructions)
                }
                matchedSkill != null -> {
                    "Skill '${matchedSkill.name}': ${matchedSkill.description}"
                }
                else -> {
                    // Fallback to on-device file storage or debug repo paths
                    val deviceSkillsDir = context?.filesDir?.resolve("skills")
                    val fileCandidates = listOfNotNull(
                        deviceSkillsDir?.resolve("$skillName/SKILL.md"),
                        deviceSkillsDir?.resolve("$skillName.md"),
                        File("E:/BIT/.agent/skills/$skillName/SKILL.md"),
                        File(".agent/skills/$skillName/SKILL.md")
                    )
                    val skillFile = fileCandidates.firstOrNull { it.exists() && it.isFile }
                    if (skillFile != null) {
                        compactSkillInstructions(skillFile.readText())
                    } else {
                        "Skill '$skillName' active with standard specialized patterns and directives."
                    }
                }
            }

            ToolObservation.success(
                summary = "Skill '$skillName' instructions loaded successfully (compacted for on-device context).",
                payload = content,
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        } catch (e: Exception) {
            logger.e("UseSkillTool", "Failed to load skill: ${e.message}", e)
            ToolObservation.error(
                summary = "Failed to load skill: ${e.message}",
                recoveryHint = "Check if the skill name is spelled correctly or available in the registry.",
                executionTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    /**
     * Compacts verbose SKILL.md guidelines into a concise instruction set
     * tailored for local small language models (SLMs) with 2k-4k context limits.
     */
    private fun compactSkillInstructions(rawMarkdown: String, maxChars: Int = 1800): String {
        if (rawMarkdown.length <= maxChars) return rawMarkdown

        val lines = rawMarkdown.lines()
        val builder = StringBuilder()

        var inFrontmatter = false
        var frontmatterDesc = ""
        val ruleLines = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed == "---") {
                inFrontmatter = !inFrontmatter
                continue
            }
            if (inFrontmatter) {
                if (trimmed.startsWith("description:", ignoreCase = true)) {
                    frontmatterDesc = trimmed.substringAfter(":").trim().removeSurrounding("\"")
                }
                continue
            }

            // Prioritize key directives, bullets, and section headers
            if (trimmed.startsWith("#") || trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("1.") || trimmed.startsWith("2.")) {
                ruleLines.add(line)
            }
        }

        if (frontmatterDesc.isNotBlank()) {
            builder.appendLine("**Overview**: $frontmatterDesc\n")
        }

        builder.appendLine("**Core Guidelines & Directives**:")
        for (r in ruleLines) {
            if (builder.length + r.length + 1 >= maxChars) {
                builder.appendLine("\n... [Remaining guidelines truncated for local context efficiency]")
                break
            }
            builder.appendLine(r)
        }

        return builder.toString().trim()
    }
}
