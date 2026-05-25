package com.example.internalchatbot.dto;

public record RenameSessionRequest(
        String title,
        boolean privateMode
) {
}
