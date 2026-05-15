package com.example.internalchatbot.dto;

import java.time.Instant;

public record StoredMessageResponse(
        Long id,
        String role,
        String content,
        Instant createdAt
) {
}
