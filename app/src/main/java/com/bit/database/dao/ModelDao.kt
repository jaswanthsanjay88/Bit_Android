package com.bit.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.bit.models.table_schema.Model

@Dao
interface ModelDao {
    @Query("SELECT * FROM models")
    suspend fun getAllOnce(): List<Model>

    @Query("SELECT * FROM models WHERE id = :id LIMIT 1")
    suspend fun getModelById(id: String): Model?
}
