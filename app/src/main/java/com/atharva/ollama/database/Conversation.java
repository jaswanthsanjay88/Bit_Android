package com.atharva.ollama.database;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Entity representing a conversation.
 */
@Entity(tableName = "conversations")
public class Conversation {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public String title;
    public long createdAt;
    public long updatedAt;

    public Conversation() {
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
        this.title = "New Chat";
    }

    @androidx.room.Ignore
    public Conversation(String title) {
        this.title = title;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = System.currentTimeMillis();
    }
}
