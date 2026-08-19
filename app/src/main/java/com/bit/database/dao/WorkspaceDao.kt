package com.bit.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import com.bit.models.table_schema.WorkspaceEntity

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspaces ORDER BY updated_at DESC")
    fun listFlow(): Flow<List<WorkspaceEntity>>

    @Query("SELECT * FROM workspaces WHERE id = :id")
    suspend fun getById(id: String): WorkspaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(workspace: WorkspaceEntity)

    @Query("SELECT * FROM workspaces")
    suspend fun getAll(): List<WorkspaceEntity>

    @Query("UPDATE workspaces SET shell_status = :shellStatus, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateShellStatus(id: String, shellStatus: String, updatedAt: Long): Int

    @Query("DELETE FROM workspaces WHERE id = :id")
    suspend fun deleteById(id: String): Int
}
