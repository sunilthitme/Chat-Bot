package com.example.internalchatbot.dto;

import jakarta.validation.constraints.NotBlank;

// Request DTO for POST /api/chat/ask.
public class ChatRequest {

    @NotBlank(message = "Message is required")
    private String message;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
