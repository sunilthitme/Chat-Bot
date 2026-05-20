package com.example.internalchatbot.dto;

public record DocumentUploadResponse(
        boolean privateMode,
        String status,
        String message
) {
}
