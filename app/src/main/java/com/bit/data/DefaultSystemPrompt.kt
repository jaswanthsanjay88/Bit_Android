package com.bit.data

object DefaultSystemPrompt {
    fun create(): SystemPromptEntry =
        SystemPromptEntry(
            title = "Default",
            systemItems = systemItems(),
            userPrependItems = userPrependItems(),
            userPostpendItems = userPostpendItems()
        )

    private fun systemItems(): List<PromptTemplateItem> = listOf(
        custom(
            """
            You are a helpful assistant in Bit.
            Answer in the user's language.
            Be accurate, concise, and honest about uncertainty.
            If the request is unclear, ask a focused clarifying question before answering.
            Do not claim access to tools, files, real-time data, or app capabilities unless Bit has made them available for the current request.
            Use Markdown when it improves readability.

            <bit_runtime_context>
            <current_date>
            """.trimIndent() + "\n"
        ),
        variable(PredefinedVariables.DATE),
        custom(
            "\n" + """
            </current_date>
            <current_time>
            """.trimIndent() + "\n"
        ),
        variable(PredefinedVariables.TIME),
        custom(
            "\n" + """
            </current_time>
            </bit_runtime_context>

            <active_memory_context>
            """.trimIndent() + "\n"
        ),
        variable(PredefinedVariables.ACTIVE_MEMORY),
        custom(
            "\n" + """
            </active_memory_context>

            Use the active memory context as relevant background for the current conversation. It may be incomplete or stale. If it conflicts with the current user message, the current user message wins. If it is empty, treat it as unavailable.
            """.trimIndent()
        )
    )

    private fun userPrependItems(): List<PromptTemplateItem> = listOf(
        custom("<user_message sent_date=\""),
        variable(PredefinedVariables.SENT_DATE),
        custom("\" sent_time=\""),
        variable(PredefinedVariables.SENT_TIME),
        custom("\">\n")
    )

    private fun userPostpendItems(): List<PromptTemplateItem> =
        listOf(custom("\n</user_message>"))

    private fun custom(value: String) =
        PromptTemplateItem(type = PromptItemType.CUSTOM, value = value)

    private fun variable(value: String) =
        PromptTemplateItem(type = PromptItemType.PREDEFINED, value = value)
}
