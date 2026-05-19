package com.example.internalchatbot.ai.retrieval;

import com.example.internalchatbot.ai.embeddings.EmbeddingService;
import com.example.internalchatbot.ai.vectorstore.VectorSearchResult;
import com.example.internalchatbot.ai.vectorstore.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final int topK;
    private final double minScore;

    public RagRetrievalService(
            EmbeddingService embeddingService,
            VectorStoreService vectorStoreService,
            @Value("${rag.top-k:5}") int topK,
            @Value("${rag.min-score:0.20}") double minScore
    ) {
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.topK = Math.max(1, Math.min(topK, 10));
        this.minScore = minScore;
    }

    public List<VectorSearchResult> retrieve(String retrievalQuestion, boolean privateMode) {
        if (privateMode || retrievalQuestion == null || retrievalQuestion.isBlank()) {
            return List.of();
        }

        try {
            List<Double> queryVector = embeddingService.embed(retrievalQuestion);
            return vectorStoreService.search(retrievalQuestion, queryVector, topK)
                    .stream()
                    .filter(result -> result.score() >= minScore)
                    .limit(topK)
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("RAG retrieval skipped because embedding generation or vector search failed.", ex);
            return List.of();
        }
    }
}
