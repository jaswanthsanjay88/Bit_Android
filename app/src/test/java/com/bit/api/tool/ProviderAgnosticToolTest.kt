package com.bit.api.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderAgnosticToolTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testToolApprovalStateTransitions() {
        val auto = ToolApprovalState.Auto
        assertFalse(auto.canResumeToolExecution())

        val pending = ToolApprovalState.Pending
        assertFalse(pending.canResumeToolExecution())

        val approved = ToolApprovalState.Approved
        assertTrue(approved.canResumeToolExecution())

        val denied = ToolApprovalState.Denied("User cancelled action")
        assertTrue(denied.canResumeToolExecution())
        assertEquals("User cancelled action", denied.reason)

        val answered = ToolApprovalState.Answered("Selected option B")
        assertTrue(answered.canResumeToolExecution())
        assertEquals("Selected option B", answered.answer)
    }

    @Test
    fun testUIMessagePartToolExecutionState() {
        val toolPart = UIMessagePart.Tool(
            toolCallId = "call_123",
            toolName = "workspace_shell",
            input = "{\"command\":\"ls -la\"}",
            approvalState = ToolApprovalState.Pending
        )

        assertTrue(toolPart.isPending)
        assertFalse(toolPart.isExecuted)
        assertFalse(toolPart.canResumeExecution)

        val approvedPart = toolPart.copy(approvalState = ToolApprovalState.Approved)
        assertTrue(approvedPart.canResumeExecution)
        assertFalse(approvedPart.isPending)

        val executedPart = approvedPart.copy(
            output = listOf(UIMessagePart.Text("total 4\n-rw-r--r-- 1 root root 12 file.txt"))
        )
        assertTrue(executedPart.isExecuted)
        assertFalse(executedPart.canResumeExecution)
    }

    @Test
    fun testToolDefinitionAndSchema() {
        val schema = InputSchema.Obj(
            properties = buildJsonObject {
                put("command", buildJsonObject {
                    put("type", "string")
                    put("description", "Command to execute")
                })
            },
            required = listOf("command")
        )

        val tool = Tool(
            name = "workspace_shell",
            description = "Run a shell command in Linux workspace",
            parameters = { schema },
            needsApproval = { true },
            execute = { args ->
                listOf(UIMessagePart.Text("Executed: $args"))
            }
        )

        assertEquals("workspace_shell", tool.name)
        val generatedSchema = tool.parameters() as InputSchema.Obj
        assertEquals(listOf("command"), generatedSchema.required)
    }
}
