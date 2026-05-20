package com.example.internalchatbot.ai.retrieval;

import com.example.internalchatbot.ai.embeddings.EmbeddingService;
import com.example.internalchatbot.ai.vectorstore.VectorSearchResult;
import com.example.internalchatbot.ai.vectorstore.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class RagRetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RagRetrievalService.class);
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "for", "from",
            "how", "i", "in", "is", "it", "me", "my", "of", "on", "or", "please",
            "the", "to", "what", "when", "where", "who", "why", "with", "you", "your"
    );

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final int topK;
    private final int candidateTopK;
    private final double minScore;

    public RagRetrievalService(
            EmbeddingService embeddingService,
            VectorStoreService vectorStoreService,
            @Value("${rag.top-k:3}") int topK,
            @Value("${rag.candidate-top-k:10}") int candidateTopK,
            @Value("${rag.min-score:0.20}") double minScore
    ) {
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.topK = Math.max(1, Math.min(topK, 10));
        this.candidateTopK = Math.max(this.topK, Math.min(candidateTopK, 20));
        this.minScore = minScore;
    }

    public List<VectorSearchResult> retrieve(String retrievalQuestion, boolean privateMode) {
        if (privateMode || retrievalQuestion == null || retrievalQuestion.isBlank()) {
            return List.of();
        }

        try {
            List<Double> queryVector = embeddingService.embed(retrievalQuestion);
            List<VectorSearchResult> candidates = vectorStoreService.search(retrievalQuestion, queryVector, candidateTopK);
            List<VectorSearchResult> reranked = rerank(retrievalQuestion, candidates);
            log.debug("RAG reranked candidates={} selected={}", candidates.size(), reranked.size());
            return reranked;
        } catch (RuntimeException ex) {
            log.warn("RAG retrieval skipped because embedding generation or vector search failed.", ex);
            return List.of();
        }
    }

    private List<VectorSearchResult> rerank(String question, List<VectorSearchResult> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        Set<String> queryTokens = tokenize(question);
        List<RankedResult> rankedResults = candidates.stream()
                .map(result -> new RankedResult(result, rerankScore(result, queryTokens, question)))
                .sorted(Comparator.comparingDouble(RankedResult::score).reversed())
                .toList();

        List<VectorSearchResult> filtered = rankedResults.stream()
                .filter(result -> result.score() >= minScore)
                .limit(topK)
                .map(RankedResult::toSearchResult)
                .toList();

        if (!filtered.isEmpty()) {
            return filtered;
        }

        return rankedResults.stream()
                .limit(topK)
                .map(RankedResult::toSearchResult)
                .toList();
    }

    private double rerankScore(VectorSearchResult result, Set<String> queryTokens, String question) {
        String content = normalize(result.content());
        String section = normalize(result.sectionTitle());
        double lexicalScore = lexicalOverlap(queryTokens, content);
        double sectionScore = lexicalOverlap(queryTokens, section);
        double phraseBoost = exactPhraseBoost(question, content);
        return (result.score() * 0.70) + (lexicalScore * 0.25) + (sectionScore * 0.10) + phraseBoost;
    }

    private double lexicalOverlap(Set<String> queryTokens, String content) {
        if (queryTokens.isEmpty() || content.isBlank()) {
            return 0;
        }
        long matches = queryTokens.stream()
                .filter(content::contains)
                .count();
        return matches / (double) queryTokens.size();
    }

    private double exactPhraseBoost(String question, String content) {
        String normalizedQuestion = normalize(question);
        if (normalizedQuestion.length() < 16 || content.isBlank()) {
            return 0;
        }
        return content.contains(normalizedQuestion) ? 0.15 : 0;
    }

    private Set<String> tokenize(String text) {
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        Arrays.stream(normalize(text).split("\\s+"))
                .map(token -> token.length() > 80 ? token.substring(0, 80) : token)
                .filter(token -> token.length() >= 3)
                .filter(token -> !STOP_WORDS.contains(token))
                .forEach(tokens::add);
        return tokens;
    }

    private String normalize(String text) {
        return text == null
                ? ""
                : text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private record RankedResult(VectorSearchResult result, double score) {
        VectorSearchResult toSearchResult() {
            return new VectorSearchResult(
                    result.embeddingId(),
                    result.sourceName(),
                    result.sourceType(),
                    result.sourceUrl(),
                    result.pageNumber(),
                    result.sectionTitle(),
                    result.content(),
                    score
            );
        }
    }
}
