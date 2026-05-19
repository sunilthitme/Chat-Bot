package com.example.internalchatbot.ai.vectorstore;

public record VectorSearchResult(
        Long embeddingId,
        String sourceName,
        String sourceType,
        String sourceUrl,
        Integer pageNumber,
        String sectionTitle,
        String content,
        double score
) {
}
