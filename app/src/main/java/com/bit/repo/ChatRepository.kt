package com.bit.repo

import com.bit.data.VaultManager
import com.bit.domain.repository.ChatRepositoryContract
import com.bit.models.messages.Messages
import com.bit.models.vault.ChatInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ChatRepository : ChatRepositoryContract {

    private val chatRepo get() = VaultManager.chatRepo ?: error("VaultManager not initialized")

    override suspend fun createChat(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val chatId = chatRepo.createChat()
            Result.success(chatId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getAllChats(): Result<List<ChatInfo>> = withContext(Dispatchers.IO) {
        try {
            val chats = chatRepo.getAllChats()
            Result.success(chats)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getMessages(chatId: String, limit: Int): Result<List<Messages>> = withContext(Dispatchers.IO) {
        try {
            val messages = chatRepo.getMessagesForChat(chatId, limit)
            Result.success(messages)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
