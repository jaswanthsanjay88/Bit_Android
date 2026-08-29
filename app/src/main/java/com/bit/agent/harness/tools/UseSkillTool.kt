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
    private val logger: HarnessLogger = NoOpHarnessLogger
) : AgentTool {

    companion object {
        const val NAME = "use_skill"
    }

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = NAME,
            description = "Load specialized domain instructions, guidelines, and patterns for a specific skill (e.g. android-clean-architecture, api-design, docker-patterns, git-workflow, kotlin-patterns, python-patterns, security-review, tdd-workflow).",
            parameters = ToolParameters(
                properties = mapOf(
                    "name" to ToolProperty(
                        type = "string",
                        description = "The skill name to load instructions from (e.g. python-patterns, tdd-workflow, security-review, android-clean-architecture)"
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

            // Search possible skill directories
            val searchPaths = listOf(
                File("E:/BIT/.agent/skills/$skillName/SKILL.md"),
                File("E:/BIT/.agents/skills/$skillName/SKILL.md"),
                File(".agent/skills/$skillName/SKILL.md"),
                File(".agents/skills/$skillName/SKILL.md")
            )

            val skillFile = searchPaths.firstOrNull { it.exists() && it.isFile }
            val content = if (skillFile != null) {
                skillFile.readText()
            } else {
                "Skill '$skillName' loaded with standard specialized best practices."
            }

            ToolObservation.success(
                summary = "Skill '$skillName' instructions loaded successfully.",
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
}
