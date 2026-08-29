package com.bit.agent.harness.engine

import com.bit.agent.harness.tools.AgentToolRegistry
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dynamic System Prompt Assembler (DeepSeek Harness paradigm).
 * Assembles system prompts dynamically from only the active/enabled tools,
 * preserving context budget on on-device LLMs.
 */
@Singleton
class SystemPromptAssembler @Inject constructor(
    private val toolRegistry: AgentToolRegistry
) {
    fun assemblePrompt(baseRolePrompt: String = ""): String {
        val toolSections = toolRegistry.tools()
            .mapNotNull { tool ->
                val prompt = tool.systemPromptContribution ?: tool.getSystemPrompt().takeIf { it.isNotBlank() }
                if (prompt != null) tool.promptOrder to prompt else null
            }
            .sortedBy { it.first }
            .map { it.second }

        return buildString {
            if (baseRolePrompt.isNotBlank()) {
                appendLine(baseRolePrompt.trim())
                appendLine()
            }
            if (toolSections.isNotEmpty()) {
                appendLine("## Tool Guidelines")
                toolSections.forEach { section ->
                    appendLine(section.trim())
                    appendLine()
                }
            }
        }.trim()
    }
}
