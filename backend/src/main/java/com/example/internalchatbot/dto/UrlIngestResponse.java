package com.example.internalchatbot.dto;

public record UrlIngestResponse(
        Long documentId,
        String sessionId,
        String url,
        int chunksStored,
        boolean privateMode,
        String summary
) {
}
