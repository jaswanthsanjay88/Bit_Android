package com.bit.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.bit.models.table_schema.KnowledgeRelation

@Dao
interface KnowledgeRelationDao {
    @Query("SELECT * FROM knowledge_relations ORDER BY created_at DESC")
    suspend fun getAll(): List<KnowledgeRelation>
}
