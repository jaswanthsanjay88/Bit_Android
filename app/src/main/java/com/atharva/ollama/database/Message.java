package com.atharva.ollama.database;

import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * Entity representing a message in a conversation.
 */
@Entity(
    tableName = "messages",
    foreignKeys = @ForeignKey(
        entity = Conversation.class,
        parentColumns = "id",
        childColumns = "conversationId",
        onDelete = ForeignKey.CASCADE
    ),
    indices = @Index("conversationId")
)
public class Message {

    @PrimaryKey(autoGenerate = true)
    public long id;

    public long conversationId;
    public String role; // "user" or "assistant"
    public String content;
    public long timestamp;

    // JSON array of image paths (local) or Base64 (avoid storing heavy base64 here if possible, better paths)
    // For now we assume paths to local temp files or gallery URIs.
    public String imagePaths; 
    
    // JSON array of sources
    public String sourcesJson;

    public Message() {
        this.timestamp = System.currentTimeMillis();
    }

    @androidx.room.Ignore
    public Message(long conversationId, String role, String content) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
    }

    public boolean isUser() {
        return "user".equals(role);
    }

    public boolean isAssistant() {
        return "assistant".equals(role);
    }
}
