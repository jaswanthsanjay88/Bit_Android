package com.bit.agent.harness.tools

import com.bit.api.ToolDefinition
import com.bit.agent.harness.model.ToolObservation

/**
 * Executes an existing PluginManager tool through the AgentToolBridge, so all
 * plugin tools (workspace, web, memory) participate in the harness action space
 * without duplicated formatting logic.
 */
class PluginBackedTool(
    override val definition: ToolDefinition,
    private val bridge: AgentToolBridge
) : AgentTool {

    override suspend fun execute(argumentsJson: String): ToolObservation {
        return bridge.execute(definition.function.name, argumentsJson)
    }
}
