package com.example.internalchatbot.ai.vectorstore;

public record VectorSearchResult(
        Long embeddingId,
        Long documentId,
        Integer chunkIndex,
        String sourceName,
        String sourceType,
        String sourceUrl,
        Integer pageNumber,
        String sectionTitle,
        String documentType,
        String language,
        String topic,
        String content,
        double score
) {
}
