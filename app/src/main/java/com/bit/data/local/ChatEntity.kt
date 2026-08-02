package com.bit.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String = "",
    val modelId: String? = null,
    val taskId: String? = null,
    val origin: String = "user",
    val graduated: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)
