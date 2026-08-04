package com.bit.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bit.models.table_schema.KnowledgeEntity

@Dao
interface KnowledgeEntityDao {
    @Query("SELECT * FROM knowledge_entities ORDER BY last_seen DESC")
    suspend fun getAll(): List<KnowledgeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: KnowledgeEntity)

    @Query("SELECT * FROM knowledge_entities WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): KnowledgeEntity?
}
