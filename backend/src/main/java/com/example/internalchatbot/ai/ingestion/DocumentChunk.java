package com.example.internalchatbot.ai.ingestion;

import java.util.Map;

public record DocumentChunk(
        String text,
        String sourceName,
        String sourceType,
        String sourceUrl,
        Integer pageNumber,
        String sectionTitle,
        String documentType,
        String language,
        String topic,
        int chunkIndex,
        int tokenEstimate,
        String contentHash,
        Map<String, String> metadata
) {
}
