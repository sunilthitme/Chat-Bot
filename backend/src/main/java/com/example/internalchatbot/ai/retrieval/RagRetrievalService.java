package com.example.internalchatbot.ai.retrieval;

import com.example.internalchatbot.ai.embeddings.EmbeddingService;
import com.example.internalchatbot.ai.vectorstore.RetrievalFilter;
import com.example.internalchatbot.ai.vectorstore.VectorSearchResult;
import com.example.internalchatbot.ai.vectorstore.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final QueryIntentClassifier queryIntentClassifier;
    private final int topK;
    private final int candidateTopK;
    private final double minScore;
    private final double intentFilterThreshold;
    private final double lowConfidenceScore;
    private final Map<String, List<VectorSearchResult>> retrievalCache;

    public RagRetrievalService(
            EmbeddingService embeddingService,
            VectorStoreService vectorStoreService,
            QueryIntentClassifier queryIntentClassifier,
            @Value("${rag.top-k:3}") int topK,
            @Value("${rag.candidate-top-k:10}") int candidateTopK,
            @Value("${rag.min-score:0.20}") double minScore,
            @Value("${rag.intent-filter-threshold:0.72}") double intentFilterThreshold,
            @Value("${rag.low-confidence-score:0.75}") double lowConfidenceScore,
            @Value("${rag.retrieval-cache-size:128}") int retrievalCacheSize
    ) {
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.queryIntentClassifier = queryIntentClassifier;
        this.topK = Math.max(1, Math.min(topK, 10));
        this.candidateTopK = Math.max(this.topK, Math.min(candidateTopK, 50));
        this.minScore = minScore;
        this.intentFilterThreshold = Math.max(0.1, Math.min(intentFilterThreshold, 0.99));
        this.lowConfidenceScore = Math.max(0.1, Math.min(lowConfidenceScore, 0.99));
        int safeCacheSize = Math.max(16, retrievalCacheSize);
        this.retrievalCache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<VectorSearchResult>> eldest) {
                return size() > safeCacheSize;
            }
        });
    }

    public List<VectorSearchResult> retrieve(String sessionId, String retrievalQuestion, boolean privateMode) {
        return retrieve(sessionId, null, null, retrievalQuestion, retrievalQuestion, privateMode);
    }

    public List<VectorSearchResult> retrieve(
            String sessionId,
            String userKey,
            Long activeDocumentId,
            String userQuestion,
            String retrievalQuestion,
            boolean privateMode
    ) {
        if (retrievalQuestion == null || retrievalQuestion.isBlank()) {
            return List.of();
        }

        try {
            long startedAt = System.nanoTime();
            String cacheKey = cacheKey(sessionId, userKey, activeDocumentId, retrievalQuestion);
            List<VectorSearchResult> cached = retrievalCache.get(cacheKey);
            if (cached != null) {
                log.info(
                        "rag retrieval cache hit sessionId={} activeDocumentId={} results={}",
                        safe(sessionId),
                        activeDocumentId,
                        cached.size()
                );
                return cached;
            }
            List<Double> queryVector = embeddingService.embed(retrievalQuestion);
            log.info(
                    "rag embedding generated sessionId={} activeDocumentId={} queryChars={} vectorDims={}",
                    safe(sessionId),
                    activeDocumentId,
                    retrievalQuestion.length(),
                    queryVector.size()
            );
            QueryIntent intent = queryIntentClassifier.classify(userQuestion);
            RetrievalFilter appliedFilter = intent.confident(intentFilterThreshold)
                    ? RetrievalFilter.enforced(
                            intent.filter().documentTypes(),
                            intent.filter().languages(),
                            intent.filter().topics()
                    )
                    : RetrievalFilter.none();
            log.info(
                    "rag query intent sessionId={} activeDocumentId={} intent={} confidence={} appliedFilter={}",
                    safe(sessionId),
                    activeDocumentId,
                    intent.name(),
                    round(intent.confidence()),
                    appliedFilter
            );

            RetrievalAttempt attempt = retrieveWithRerank(
                    "metadata-filtered",
                    sessionId,
                    userKey,
                    activeDocumentId,
                    retrievalQuestion,
                    queryVector,
                    appliedFilter
            );

            if (weak(attempt.results())) {
                log.info(
                        "rag fallback activated reason=low-confidence-semantic topScore={} threshold={} previousMode={}",
                        topScore(attempt.results()),
                        lowConfidenceScore,
                        attempt.mode()
                );
                attempt = retrieveWithRerank(
                        "semantic",
                        sessionId,
                        userKey,
                        activeDocumentId,
                        retrievalQuestion,
                        queryVector,
                        RetrievalFilter.none()
                );
            }

            if (weak(attempt.results())) {
                log.info(
                        "rag fallback activated reason=keyword-hybrid topScore={} threshold={} previousMode={}",
                        topScore(attempt.results()),
                        lowConfidenceScore,
                        attempt.mode()
                );
                attempt = retrieveWithRerank(
                        "keyword-hybrid",
                        sessionId,
                        userKey,
                        activeDocumentId,
                        retrievalQuestion,
                        queryVector,
                        intent.filter()
                );
            }

            log.info(
                    "rag retrieval completed sessionId={} activeDocumentId={} mode={} candidates={} selected={} topScores={} totalMs={}",
                    safe(sessionId),
                    activeDocumentId,
                    attempt.mode(),
                    attempt.candidateCount(),
                    attempt.results().size(),
                    topScores(attempt.results()),
                    elapsedMillis(startedAt)
            );
            List<VectorSearchResult> selected = List.copyOf(attempt.results());
            retrievalCache.put(cacheKey, selected);
            return selected;
        } catch (RuntimeException ex) {
            log.warn("RAG retrieval skipped because embedding generation or vector search failed.", ex);
            return List.of();
        }
    }

    private RetrievalAttempt retrieveWithRerank(
            String mode,
            String sessionId,
            String userKey,
            Long activeDocumentId,
            String retrievalQuestion,
            List<Double> queryVector,
            RetrievalFilter filter
    ) {
        List<VectorSearchResult> candidates = vectorStoreService.search(
                    sessionId,
                    userKey,
                    activeDocumentId,
                    retrievalQuestion,
                    queryVector,
                    candidateTopK,
                    filter
            );
        List<VectorSearchResult> reranked = rerank(retrievalQuestion, candidates, mode);
        return new RetrievalAttempt(mode, candidates.size(), reranked);
    }

    private List<VectorSearchResult> rerank(String question, List<VectorSearchResult> candidates, String mode) {
        if (candidates.isEmpty()) {
            return List.of();
        }

        Set<String> queryTokens = tokenize(question);
        List<RankedResult> rankedResults = candidates.stream()
                .map(result -> new RankedResult(result, rerankScore(result, queryTokens, question)))
                .sorted(Comparator.comparingDouble(RankedResult::score).reversed())
                .toList();
        log.info("rag reranking mode={} candidates={} scores={}", mode, candidates.size(), rankedScores(rankedResults));

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
        double codeBoost = codeIntentBoost(question, result);
        return (result.score() * 0.65) + (lexicalScore * 0.30) + (sectionScore * 0.12) + phraseBoost + codeBoost;
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

    private double codeIntentBoost(String question, VectorSearchResult result) {
        String normalizedQuestion = normalize(question);
        if (!containsAny(normalizedQuestion, "code", "java", "class", "method", "api", "configuration", "controller")) {
            return 0;
        }
        double boost = 0;
        if ("code".equals(normalize(result.documentType()))) {
            boost += 0.10;
        }
        if ("java".equals(normalize(result.language()))) {
            boost += 0.10;
        }
        if (normalize(result.content()).contains("public ") || normalize(result.content()).contains("@restcontroller")) {
            boost += 0.06;
        }
        return boost;
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
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

    private String topScores(List<VectorSearchResult> results) {
        return results.stream()
                .limit(5)
                .map(result -> result.sourceName() + ":" + Math.round(result.score() * 1000.0) / 1000.0)
                .toList()
                .toString();
    }

    private String rankedScores(List<RankedResult> results) {
        return results.stream()
                .limit(8)
                .map(result -> result.result().sourceName() + ":" + round(result.score()))
                .toList()
                .toString();
    }

    private boolean weak(List<VectorSearchResult> results) {
        return results.isEmpty() || topScore(results) < lowConfidenceScore;
    }

    private double topScore(List<VectorSearchResult> results) {
        return results.stream()
                .mapToDouble(VectorSearchResult::score)
                .max()
                .orElse(0);
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private String cacheKey(String sessionId, String userKey, Long activeDocumentId, String retrievalQuestion) {
        return defaultString(sessionId)
                + "|" + defaultString(userKey)
                + "|" + (activeDocumentId == null ? "" : activeDocumentId)
                + "|" + normalize(retrievalQuestion);
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private record RankedResult(VectorSearchResult result, double score) {
        VectorSearchResult toSearchResult() {
            return new VectorSearchResult(
                    result.embeddingId(),
                    result.documentId(),
                    result.chunkIndex(),
                    result.sourceName(),
                    result.sourceType(),
                    result.sourceUrl(),
                    result.pageNumber(),
                    result.sectionTitle(),
                    result.documentType(),
                    result.language(),
                    result.topic(),
                    result.content(),
                    score
            );
        }
    }

    private record RetrievalAttempt(String mode, int candidateCount, List<VectorSearchResult> results) {
    }
}
