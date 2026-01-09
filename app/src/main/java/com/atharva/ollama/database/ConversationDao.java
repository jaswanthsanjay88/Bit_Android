package com.atharva.ollama.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object for conversations.
 */
@Dao
public interface ConversationDao {

    @Insert
    long insert(Conversation conversation);

    @Update
    void update(Conversation conversation);

    @Delete
    void delete(Conversation conversation);

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    List<Conversation> getAllConversations();

    @Query("SELECT * FROM conversations WHERE id = :id")
    Conversation getConversationById(long id);

    @Query("DELETE FROM conversations WHERE id = :id")
    void deleteById(long id);

    @Query("UPDATE conversations SET updatedAt = :timestamp WHERE id = :id")
    void updateTimestamp(long id, long timestamp);

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    void updateTitle(long id, String title);
}
