package com.atharva.ollama.model;

/**
 * Model class representing a chat message for UI display.
 */
public class ChatMessage {

    public enum Type {
        USER,
        ASSISTANT,
        LOADING
    }

    private final Type type;
    private String content;
    private final long timestamp;
    private final long databaseId;

    public ChatMessage(Type type, String content) {
        this.type = type;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
        this.databaseId = -1;
    }

    public ChatMessage(Type type, String content, long timestamp) {
        this.type = type;
        this.content = content;
        this.timestamp = timestamp;
        this.databaseId = -1;
    }

    public ChatMessage(Type type, String content, long timestamp, long databaseId) {
        this.type = type;
        this.content = content;
        this.timestamp = timestamp;
        this.databaseId = databaseId;
    }

    // New fields
    private java.util.List<String> images = new java.util.ArrayList<>();
    private java.util.List<Source> sources = new java.util.ArrayList<>();

    public java.util.List<String> getImages() {
        return images;
    }

    public void setImages(java.util.List<String> images) {
        this.images = images;
    }

    public java.util.List<Source> getSources() {
        return sources;
    }

    public void setSources(java.util.List<Source> sources) {
        this.sources = sources;
    }

    /**
     * Data class for a source citation.
     */
    public static class Source {
        public String title;
        public String url;
        public String snippet;

        public Source(String title, String url, String snippet) {
            this.title = title;
            this.url = url;
            this.snippet = snippet;
        }
    }

    public Type getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public void appendContent(String text) {
        this.content = this.content + text;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getDatabaseId() {
        return databaseId;
    }

    public boolean isUser() {
        return type == Type.USER;
    }

    public boolean isAssistant() {
        return type == Type.ASSISTANT;
    }

    public boolean isLoading() {
        return type == Type.LOADING;
    }

    // Status for loading state
    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
