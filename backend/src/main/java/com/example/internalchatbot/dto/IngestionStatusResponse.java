package com.example.internalchatbot.dto;

public record IngestionStatusResponse(
        boolean active,
        String status,
        String stage,
        String message,
        String summary,
        boolean privateMode
) {
}
