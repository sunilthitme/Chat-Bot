package com.example.internalchatbot.dto;

public record CreateSessionRequest(
        String title,
        String userKey,
        boolean privateMode
) {
}
