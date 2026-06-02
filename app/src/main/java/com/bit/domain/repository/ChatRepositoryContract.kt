package com.bit.domain.repository

import com.bit.models.messages.Messages
import com.bit.models.vault.ChatInfo

/**
 * Contract for chat data access operations.
 * Enables testability via fakes and decouples ViewModels from implementations.
 */
interface ChatRepositoryContract {
    suspend fun createChat(): Result<String>
    suspend fun getAllChats(): Result<List<ChatInfo>>
    suspend fun getMessages(chatId: String, limit: Int = 1000): Result<List<Messages>>
}
