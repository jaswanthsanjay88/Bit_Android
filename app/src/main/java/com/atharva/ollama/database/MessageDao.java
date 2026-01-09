package com.atharva.ollama.database;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;

import java.util.List;

/**
 * Data Access Object for messages.
 */
@Dao
public interface MessageDao {

    @Insert
    long insert(Message message);

    @Delete
    void delete(Message message);

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    List<Message> getMessagesForConversation(long conversationId);

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    void deleteAllMessagesInConversation(long conversationId);

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    List<Message> getRecentMessages(long conversationId, int limit);

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    int getMessageCount(long conversationId);

    @Query("SELECT content FROM messages WHERE conversationId = :conversationId AND role = 'user' ORDER BY timestamp ASC LIMIT 1")
    String getFirstUserMessage(long conversationId);
}
