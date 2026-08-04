package com.bit.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.bit.models.table_schema.KnowledgeRelation

@Dao
interface KnowledgeRelationDao {
    @Query("SELECT * FROM knowledge_relations ORDER BY created_at DESC")
    suspend fun getAll(): List<KnowledgeRelation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(relation: KnowledgeRelation)

    @Query("DELETE FROM knowledge_relations WHERE source_fact_id = :factId")
    suspend fun deleteByFactId(factId: String)
}
