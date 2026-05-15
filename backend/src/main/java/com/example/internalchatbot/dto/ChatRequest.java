package com.example.internalchatbot.dto;

import jakarta.validation.constraints.NotBlank;

// Request DTO for POST /api/chat/ask.
public class ChatRequest {

    @NotBlank(message = "Message is required")
    private String message;

    private String sessionId;

    private String userKey;

    private boolean privateMode;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserKey() {
        return userKey;
    }

    public void setUserKey(String userKey) {
        this.userKey = userKey;
    }

    public boolean isPrivateMode() {
        return privateMode;
    }

    public void setPrivateMode(boolean privateMode) {
        this.privateMode = privateMode;
    }
}
