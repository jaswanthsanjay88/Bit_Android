package com.bit.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.bit.models.table_schema.Persona

@Dao
interface PersonaDao {
    @Query("SELECT * FROM personas ORDER BY created_at ASC")
    suspend fun getAllOnce(): List<Persona>
}
