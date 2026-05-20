package com.example.internalchatbot.dto;

public record UrlIngestResponse(
        boolean privateMode,
        String status,
        String message
) {
}
