package com.example.internalchatbot.ai.retrieval;

public record LuceneSearchResult(
        Long embeddingId,
        float score
) {
}
