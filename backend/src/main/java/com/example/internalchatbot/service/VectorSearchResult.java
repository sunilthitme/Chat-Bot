package com.example.internalchatbot.service;

public record VectorSearchResult(
        Long embeddingId,
        String sourceName,
        String sourceType,
        String content,
        double score
) {
}
