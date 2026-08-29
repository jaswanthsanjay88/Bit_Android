package com.bit.agent.harness.model

import com.bit.api.ChatMessage
import com.bit.api.Participant
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Immutable Session Events recorded in the append-only event log (DeepSeek Harness paradigm).
 * "Model-visible means logged" — every action, observation, and state is reconstructable.
 */
sealed class HarnessSessionEvent {
    abstract val timestamp: Long

    data class TurnStart(
        val turnId: String,
        val goal: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HarnessSessionEvent()

    data class StepStart(
        val turnId: String,
        val stepId: String,
        val toolName: String,
        val arguments: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HarnessSessionEvent()

    data class ToolResultRecorded(
        val turnId: String,
        val stepId: String,
        val toolName: String,
        val observation: ToolObservation,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HarnessSessionEvent()

    data class StepEnd(
        val turnId: String,
        val stepId: String,
        val status: String,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HarnessSessionEvent()

    data class TurnEnd(
        val turnId: String,
        val finalResult: String,
        val success: Boolean,
        override val timestamp: Long = System.currentTimeMillis()
    ) : HarnessSessionEvent()
}

/**
 * Append-only Session Log with deterministic projection capabilities.
 */
@Singleton
class HarnessSessionLog @Inject constructor() {
    private val _events = CopyOnWriteArrayList<HarnessSessionEvent>()
    val events: List<HarnessSessionEvent> get() = _events.toList()

    fun append(event: HarnessSessionEvent) {
        _events.add(event)
    }

    /**
     * Projects model-visible conversation message history directly from the event log.
     */
    fun deriveMessages(systemPrompt: String = ""): List<ChatMessage> {
        val messages = mutableListOf<ChatMessage>()

        if (systemPrompt.isNotBlank()) {
            messages.add(ChatMessage(text = systemPrompt, participant = Participant.USER))
        }

        var currentGoal = ""
        for (event in _events) {
            when (event) {
                is HarnessSessionEvent.TurnStart -> {
                    currentGoal = event.goal
                    messages.add(ChatMessage(text = event.goal, participant = Participant.USER))
                }
                is HarnessSessionEvent.StepStart -> {
                    messages.add(
                        ChatMessage(
                            text = "Calling tool '${event.toolName}' with arguments: ${event.arguments}",
                            participant = Participant.MODEL
                        )
                    )
                }
                is HarnessSessionEvent.ToolResultRecorded -> {
                    val obs = event.observation
                    val content = obs.payload?.takeIf { it.isNotBlank() } ?: obs.summary
                    messages.add(
                        ChatMessage(
                            text = "[Tool Observation: ${event.toolName}]\n$content",
                            participant = Participant.USER
                        )
                    )
                }
                is HarnessSessionEvent.TurnEnd -> {
                    if (event.finalResult.isNotBlank()) {
                        messages.add(ChatMessage(text = event.finalResult, participant = Participant.MODEL))
                    }
                }
                else -> {}
            }
        }
        return messages
    }

    fun clear() {
        _events.clear()
    }
}
