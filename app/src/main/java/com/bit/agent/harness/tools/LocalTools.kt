package com.bit.agent.harness.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.bit.agent.harness.model.ObservationStatus
import com.bit.agent.harness.model.ToolObservation
import com.bit.api.ToolDefinition
import com.bit.api.ToolFunction
import com.bit.api.ToolParameters
import com.bit.api.ToolProperty
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * Tool for retrieving local device date, time, timezone, and calendar info.
 */
class TimeInfoTool : AgentTool {
    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = "get_time_info",
            description = "Get the current local date, time, weekday, timezone, and ISO timestamp from the device.",
            parameters = ToolParameters(properties = emptyMap())
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val now = ZonedDateTime.now()
        val date = now.toLocalDate()
        val time = now.toLocalTime().withNano(0)
        val weekday = now.dayOfWeek
        val json = JSONObject().apply {
            put("year", date.year)
            put("month", date.monthValue)
            put("day", date.dayOfMonth)
            put("weekday", weekday.getDisplayName(TextStyle.FULL, Locale.getDefault()))
            put("date", date.toString())
            put("time", time.toString())
            put("datetime", now.withNano(0).toString())
            put("timezone", now.zone.id)
            put("utc_offset", now.offset.id)
            put("timestamp_ms", now.toInstant().toEpochMilli())
        }
        return ToolObservation.success(
            summary = "Device time: ${date} ${time} (${now.zone.id})",
            payload = json.toString()
        )
    }
}

/**
 * Tool for reading or writing to the Android clipboard.
 * Reading requires user approval — silent clipboard access is a privacy leak.
 */
class ClipboardTool(private val context: Context) : AgentTool {
    override val requiresApproval: Boolean = true

    override val definition: ToolDefinition = ToolDefinition(
        type = "function",
        function = ToolFunction(
            name = "clipboard_tool",
            description = "Read or write plain text from the device clipboard. Use action: 'read' or 'write'.",
            parameters = ToolParameters(
                properties = mapOf(
                    "action" to ToolProperty(
                        type = "string",
                        description = "Operation to perform: 'read' or 'write'"
                    ),
                    "text" to ToolProperty(
                        type = "string",
                        description = "Text to copy to clipboard (required for write)"
                    )
                ),
                required = listOf("action")
            )
        )
    )

    override suspend fun execute(argumentsJson: String): ToolObservation {
        val args = if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
        val action = args.optString("action", "read")
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return ToolObservation.error("Clipboard service unavailable", "Device does not support clipboard")

        return when (action) {
            "read" -> {
                val clip = cm.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(context).toString()
                } else ""
                ToolObservation.success(
                    summary = "Read ${text.length} characters from clipboard",
                    payload = text
                )
            }
            "write" -> {
                val text = args.optString("text", "")
                val clip = ClipData.newPlainText("BIT AI", text)
                cm.setPrimaryClip(clip)
                ToolObservation.success(
                    summary = "Copied text to clipboard",
                    payload = text
                )
            }
            else -> ToolObservation.error("Unknown clipboard action: $action", "Use 'read' or 'write'")
        }
    }
}
