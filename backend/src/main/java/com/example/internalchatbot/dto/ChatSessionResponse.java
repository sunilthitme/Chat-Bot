package com.example.internalchatbot.dto;

import java.time.Instant;

public record ChatSessionResponse(
        String id,
        String title,
        boolean privateMode,
        Instant createdAt,
        Instant updatedAt
) {
}
