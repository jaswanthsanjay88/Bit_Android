package com.bit.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.bit.model.MessageStatus
import com.bit.model.Participant
import com.bit.model.RunStatus

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val parentId: String? = null,
    val text: String,
    val thoughts: String? = null,
    val status: MessageStatus = MessageStatus.SUCCESS,
    val participant: Participant = Participant.USER,
    val timestamp: Long = System.currentTimeMillis(),
    val modelName: String? = null,
    val runId: String? = null,
    val runSequence: Int = 0,
    val consumedAtPass: Int = 0
)

@Entity(tableName = "runs")
data class RunEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val parentRunId: String? = null,
    val status: RunStatus = RunStatus.ACTIVE,
    val activeSlot: Int = 1,
    val startedAt: Long = System.currentTimeMillis(),
    val lastCheckpointAt: Long = System.currentTimeMillis()
)
