package com.bit.models.table_schema

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "memory_notes",
    indices = [
        Index(value = ["is_ai_memory_enabled"]),
        Index(value = ["note_type"]),
        Index(value = ["folder"]),
        Index(value = ["status"]),
        Index(value = ["conflict_with_fact_id"]),
        Index(value = ["updated_at"])
    ]
)
data class MemoryNote(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "tags")
    val tags: String = "",

    @ColumnInfo(name = "note_type")
    val noteType: String = "note", // "fact", "note", "task", "document"

    @ColumnInfo(name = "folder")
    val folder: String = "notes", // "notes", "ai_memory", "documents"

    @ColumnInfo(name = "status")
    val status: String = "todo", // "todo", "doing", "done"

    @ColumnInfo(name = "due_date")
    val dueDate: Long? = null,

    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,

    @ColumnInfo(name = "file_path")
    val filePath: String = "",

    @ColumnInfo(name = "conflict_with_fact_id")
    val conflictWithFactId: String? = null,

    @ColumnInfo(name = "last_backed_up_at")
    val lastBackedUpAt: Long = 0L,

    @ColumnInfo(name = "is_pinned")
    val isPinned: Boolean = false,

    @ColumnInfo(name = "access_count")
    val accessCount: Int = 1,

    @ColumnInfo(name = "is_ai_memory_enabled")
    val isAiMemoryEnabled: Boolean = true,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
)
