package com.bit.data.repository

import com.bit.data.local.ChatEntity
import com.bit.data.local.MessageEntity
import com.bit.data.local.RunEntity
import com.bit.model.ChatConversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    suspend fun getConversation(id: String): ChatEntity?
    suspend fun upsertConversation(conversation: ChatEntity)
    suspend fun deleteConversation(id: String)
    fun getExecutionsForTask(taskId: String): Flow<List<ChatConversation>>
    fun observeExecutionMessagesForTask(taskId: String): Flow<List<MessageEntity>>

    suspend fun getMessagesForConversationSnapshot(conversationId: String): List<MessageEntity>
    suspend fun createRunWithMessages(run: RunEntity, messages: List<MessageEntity>)
    suspend fun selectRunBranch(conversationId: String, parentRunId: String?, runId: String)
    suspend fun updateStreamingMessageCheckpoint(message: com.bit.model.ChatMessage)
    suspend fun finishRunStopped(runId: String)
    suspend fun failRun(runId: String)
    suspend fun upsertMessage(message: MessageEntity)
    fun restoreBranchSelections(conversationId: String): Map<String, String>
}
