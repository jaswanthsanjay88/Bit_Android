package com.bit.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.bit.models.table_schema.MemoryNote
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryNoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: MemoryNote)

    @Update
    suspend fun updateNote(note: MemoryNote)

    @Delete
    suspend fun deleteNote(note: MemoryNote)

    @Query("DELETE FROM memory_notes WHERE id = :id")
    suspend fun deleteNoteById(id: String)

    @Query("DELETE FROM memory_notes")
    suspend fun deleteAll()

    @Query("SELECT * FROM memory_notes ORDER BY is_pinned DESC, updated_at DESC")
    fun getAllNotesFlow(): Flow<List<MemoryNote>>

    @Query("SELECT * FROM memory_notes ORDER BY updated_at DESC")
    suspend fun getAllNotesOnce(): List<MemoryNote>

    @Query("SELECT * FROM memory_notes WHERE is_ai_memory_enabled = 1 ORDER BY updated_at DESC")
    suspend fun getAiEnabledNotesOnce(): List<MemoryNote>

    @Query("SELECT * FROM memory_notes WHERE id = :id LIMIT 1")
    suspend fun getNoteById(id: String): MemoryNote?

    @Query("SELECT * FROM memory_notes WHERE folder = :folder ORDER BY is_pinned DESC, updated_at DESC")
    fun getNotesByFolderFlow(folder: String): Flow<List<MemoryNote>>

    @Query("SELECT * FROM memory_notes WHERE note_type = :type ORDER BY is_pinned DESC, updated_at DESC")
    fun getNotesByTypeFlow(type: String): Flow<List<MemoryNote>>

    @Query("SELECT * FROM memory_notes WHERE conflict_with_fact_id IS NOT NULL ORDER BY updated_at DESC")
    fun getConflictingNotesFlow(): Flow<List<MemoryNote>>

    @Query("SELECT * FROM memory_notes WHERE note_type = 'task' ORDER BY updated_at DESC")
    fun getTasksFlow(): Flow<List<MemoryNote>>
}
