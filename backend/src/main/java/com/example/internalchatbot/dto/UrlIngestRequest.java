package com.example.internalchatbot.dto;

import jakarta.validation.constraints.NotBlank;

public record UrlIngestRequest(
        @NotBlank(message = "URL is required")
        String url,
        String sessionId,
        String userKey,
        boolean privateMode,
        boolean loginRequired
) {
}
