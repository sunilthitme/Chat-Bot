package com.example.internalchatbot.dto;

public record DocumentUploadResponse(
        Long documentId,
        String sessionId,
        String sourceName,
        int chunksStored,
        boolean privateMode,
        String message
) {
}
