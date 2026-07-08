package com.bit.data

import kotlinx.serialization.Serializable
import java.util.UUID

enum class PromptItemType { CUSTOM, PREDEFINED }

@Serializable
data class PromptTemplateItem(
    val id: String = UUID.randomUUID().toString(),
    val type: PromptItemType,
    val value: String
)

object PredefinedVariables {
    const val TIME = "time"
    const val DATE = "date"
    const val SENT_TIME = "sent_time"
    const val SENT_DATE = "sent_date"
    const val ACTIVE_MEMORY = "active_memory"
    const val MODEL_ID = "model_id"

    val ALL = listOf(TIME, DATE, SENT_TIME, SENT_DATE, ACTIVE_MEMORY, MODEL_ID)
    val PER_MESSAGE_VARS = setOf(SENT_TIME, SENT_DATE)

    val EXAMPLE_VALUES: Map<String, String>
        get() {
            val currentDateTime = java.util.Date()
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            val dateSdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
            return mapOf(
                TIME to sdf.format(currentDateTime),
                DATE to dateSdf.format(currentDateTime),
                SENT_TIME to sdf.format(currentDateTime),
                SENT_DATE to dateSdf.format(currentDateTime),
                ACTIVE_MEMORY to "[Example memory content]",
                MODEL_ID to "default-model"
            )
        }

    fun compile(
        items: List<PromptTemplateItem>,
        runtimeValues: Map<String, String>,
        exampleValues: Map<String, String> = EXAMPLE_VALUES
    ): String {
        return items.joinToString("") { item ->
            when (item.type) {
                PromptItemType.CUSTOM -> item.value
                PromptItemType.PREDEFINED -> runtimeValues[item.value]
                    ?: exampleValues[item.value]
                    ?: "{${item.value}}"
            }
        }
    }
}

@Serializable
data class SystemPromptEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String = "",
    val systemItems: List<PromptTemplateItem> = emptyList(),
    val userPrependItems: List<PromptTemplateItem> = emptyList(),
    val userPostpendItems: List<PromptTemplateItem> = emptyList()
) {
    val resolvedSystemItems: List<PromptTemplateItem>
        get() = if (systemItems.isNotEmpty()) systemItems
        else if (content.isNotBlank()) listOf(PromptTemplateItem(type = PromptItemType.CUSTOM, value = content))
        else emptyList()
}
