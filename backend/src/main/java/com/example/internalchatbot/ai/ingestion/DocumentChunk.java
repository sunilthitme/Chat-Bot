package com.example.internalchatbot.ai.ingestion;

import java.util.Map;

public record DocumentChunk(
        String text,
        String sourceName,
        String sourceType,
        String sourceUrl,
        Integer pageNumber,
        String sectionTitle,
        int chunkIndex,
        int tokenEstimate,
        String contentHash,
        Map<String, String> metadata
) {
}
