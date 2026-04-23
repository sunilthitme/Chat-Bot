package com.example.internalchatbot.dto;

import jakarta.validation.constraints.NotBlank;

// Request DTO used by the frontend to send timeout or client-side errors to backend logs.
public class ClientLogRequest {

    @NotBlank(message = "Message is required")
    private String message;

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
