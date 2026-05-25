package com.example.internalchatbot.ai.vectorstore;

import com.example.internalchatbot.ai.ingestion.ContentHash;
import com.example.internalchatbot.ai.ingestion.DocumentChunk;
import com.example.internalchatbot.ai.retrieval.LuceneIndexService;
import com.example.internalchatbot.ai.retrieval.LuceneSearchResult;
import com.example.internalchatbot.ai.retrieval.RetrievalScope;
import com.example.internalchatbot.entity.EmbeddingMetadata;
import com.example.internalchatbot.repository.EmbeddingMetadataRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);
    private static final TypeReference<List<Double>> VECTOR_TYPE = new TypeReference<>() {
    };

    private final EmbeddingMetadataRepository embeddingMetadataRepository;
    private final LuceneIndexService luceneIndexService;
    private final ObjectMapper objectMapper;
    private final int localCandidateLimit;
    private final boolean allowGlobalRetrieval;
    private final int neighborExpansionLimit;
    private final int expandedCandidateLimit;

    public VectorStoreService(
            EmbeddingMetadataRepository embeddingMetadataRepository,
            LuceneIndexService luceneIndexService,
            @Value("${rag.local-candidate-limit:600}") int localCandidateLimit,
            @Value("${rag.allow-global-retrieval:false}") boolean allowGlobalRetrieval,
            @Value("${rag.neighbor-expansion-limit:1}") int neighborExpansionLimit,
            @Value("${rag.expanded-candidate-limit:32}") int expandedCandidateLimit
    ) {
        this.embeddingMetadataRepository = embeddingMetadataRepository;
        this.luceneIndexService = luceneIndexService;
        this.objectMapper = new ObjectMapper();
        this.localCandidateLimit = Math.max(50, localCandidateLimit);
        this.allowGlobalRetrieval = allowGlobalRetrieval;
        this.neighborExpansionLimit = Math.max(0, Math.min(neighborExpansionLimit, 2));
        this.expandedCandidateLimit = Math.max(5, expandedCandidateLimit);
    }

    public EmbeddingMetadata store(
            String namespace,
            String sessionId,
            Long documentId,
            String sourceName,
            String sourceType,
            String content,
            List<Double> vector,
            boolean privateMode
    ) {
        return store(namespace, sessionId, null, documentId, sourceName, sourceType, content, vector, privateMode);
    }

    public EmbeddingMetadata store(
            String namespace,
            String sessionId,
            String userKey,
            Long documentId,
            String sourceName,
            String sourceType,
            String content,
            List<Double> vector,
            boolean privateMode
    ) {
        DocumentChunk chunk = new DocumentChunk(
                content,
                sourceName,
                sourceType,
                null,
                null,
                null,
                inferDocumentType(sourceName, sourceType),
                inferLanguage(sourceName),
                "general",
                0,
                Math.max(1, content == null ? 0 : (int) Math.ceil(content.length() / 4.0)),
                ContentHash.sha256(content),
                Map.of()
        );
        return store(namespace, sessionId, userKey, documentId, chunk, vector, privateMode);
    }

    public EmbeddingMetadata store(
            String namespace,
            String sessionId,
            Long documentId,
            DocumentChunk chunk,
            List<Double> vector,
            boolean privateMode
    ) {
        return store(namespace, sessionId, null, documentId, chunk, vector, privateMode);
    }

    public EmbeddingMetadata store(
            String namespace,
            String sessionId,
            String userKey,
            Long documentId,
            DocumentChunk chunk,
            List<Double> vector,
            boolean privateMode
    ) {
        if (privateMode) {
            throw new IllegalArgumentException("Private-mode content must not be stored in long-term RAG memory");
        }

        if (documentId != null) {
            var existing = embeddingMetadataRepository.findFirstByDocumentIdAndContentHashAndPrivateModeFalse(
                    documentId,
                    chunk.contentHash()
            );
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        EmbeddingMetadata metadata = new EmbeddingMetadata();
        metadata.setNamespace(namespace);
        metadata.setSessionId(sessionId);
        metadata.setUserKey(userKey);
        metadata.setDocumentId(documentId);
        metadata.setSourceName(chunk.sourceName());
        metadata.setSourceType(chunk.sourceType());
        metadata.setSourceUrl(chunk.sourceUrl());
        metadata.setPageNumber(chunk.pageNumber());
        metadata.setSectionTitle(chunk.sectionTitle());
        metadata.setDocumentType(chunk.documentType());
        metadata.setLanguage(chunk.language());
        metadata.setTopic(chunk.topic());
        metadata.setChunkIndex(chunk.chunkIndex());
        metadata.setTokenEstimate(chunk.tokenEstimate());
        metadata.setContentHash(chunk.contentHash());
        metadata.setContentChunk(chunk.text());
        metadata.setVectorJson(toJson(vector));
        metadata.setMetadataJson(toJson(chunk.metadata()));
        metadata.setPrivateMode(false);

        EmbeddingMetadata saved = embeddingMetadataRepository.save(metadata);
        luceneIndexService.index(saved);
        log.debug(
                "stored local embedding embeddingId={} documentId={} sessionId={} chunkIndex={} vectorDims={}",
                saved.getId(),
                saved.getDocumentId(),
                safe(saved.getSessionId()),
                saved.getChunkIndex(),
                vector == null ? 0 : vector.size()
        );
        return saved;
    }

    public List<VectorSearchResult> search(List<Double> queryVector, int topK) {
        return search(null, null, null, "", queryVector, topK, RetrievalFilter.none());
    }

    public List<VectorSearchResult> search(String queryText, List<Double> queryVector, int topK) {
        return search(null, null, null, queryText, queryVector, topK, RetrievalFilter.none());
    }

    public List<VectorSearchResult> search(
            String sessionId,
            String userKey,
            Long activeDocumentId,
            String queryText,
            List<Double> queryVector,
            int topK,
            RetrievalFilter filter
    ) {
        if ((queryVector == null || queryVector.isEmpty()) && (queryText == null || queryText.isBlank())) {
            return List.of();
        }
        if (!hasSearchScope(sessionId, userKey, activeDocumentId) && !allowGlobalRetrieval) {
            log.info("hybrid search skipped because no retrieval scope was available and global retrieval is disabled");
            return List.of();
        }

        long startedAt = System.nanoTime();
        RetrievalScope scope = new RetrievalScope(sessionId, userKey, activeDocumentId);
        int candidateCount = Math.max(topK * 2, topK);
        Map<Long, Double> luceneScores = luceneScores(luceneIndexService.search(scope, queryText, filter, candidateCount));
        List<EmbeddingMetadata> localCandidates = localCandidates(sessionId, userKey, activeDocumentId);
        Map<Long, EmbeddingMetadata> candidates = new LinkedHashMap<>();
        localCandidates.forEach(metadata -> candidates.put(metadata.getId(), metadata));
        loadByIds(luceneScores.keySet()).forEach(metadata -> candidates.put(metadata.getId(), metadata));

        Map<String, VectorSearchResult> merged = new LinkedHashMap<>();
        for (EmbeddingMetadata metadata : candidates.values()) {
            if (filter != null && filter.enforce()
                    && !filter.matches(metadata.getDocumentType(), metadata.getLanguage(), metadata.getTopic())) {
                continue;
            }
            putBest(merged, toSearchResult(
                    metadata,
                    queryVector == null ? List.of() : queryVector,
                    queryText,
                    sessionId,
                    filter,
                    luceneScores.getOrDefault(metadata.getId(), 0.0)
            ));
        }

        List<VectorSearchResult> ranked = merged.values()
                .stream()
                .sorted(Comparator.comparingDouble(VectorSearchResult::score).reversed())
                .limit(topK)
                .toList();
        List<VectorSearchResult> expanded = expandNeighbors(ranked, queryVector, queryText, sessionId, filter);
        log.info(
                "hybrid search completed sessionId={} activeDocumentId={} userKey={} scope={} localCandidates={} luceneHits={} merged={} expanded={} topScores={} totalMs={}",
                safe(sessionId),
                activeDocumentId,
                safe(userKey),
                scopeLabel(scope),
                localCandidates.size(),
                luceneScores.size(),
                merged.size(),
                expanded.size(),
                scoreSummary(expanded),
                elapsedMillis(startedAt)
        );
        return expanded.stream()
                .sorted(Comparator.comparingDouble(VectorSearchResult::score).reversed())
                .limit(Math.min(expandedCandidateLimit, Math.max(topK, expanded.size())))
                .toList();
    }

    public void deleteByDocumentId(Long documentId) {
        List<EmbeddingMetadata> embeddings = embeddingMetadataRepository.findByDocumentIdAndPrivateModeFalse(documentId);
        luceneIndexService.deleteByDocumentId(documentId);
        embeddingMetadataRepository.deleteAll(embeddings);
    }

    private List<EmbeddingMetadata> localCandidates(String sessionId, String userKey, Long activeDocumentId) {
        List<EmbeddingMetadata> candidates = new ArrayList<>();
        if (activeDocumentId != null) {
            if (sessionId != null && !sessionId.isBlank()) {
                candidates.addAll(embeddingMetadataRepository.findByDocumentIdAndSessionIdAndPrivateModeFalseOrderByChunkIndexAsc(
                        activeDocumentId,
                        sessionId,
                        PageRequest.of(0, localCandidateLimit)
                ));
            } else {
                candidates.addAll(embeddingMetadataRepository.findByDocumentIdAndPrivateModeFalseOrderByChunkIndexAsc(
                        activeDocumentId,
                        PageRequest.of(0, localCandidateLimit)
                ));
            }
            return candidates;
        }
        if (sessionId != null && !sessionId.isBlank()) {
            candidates.addAll(embeddingMetadataRepository.findBySessionIdAndPrivateModeFalseOrderByCreatedAtDesc(
                    sessionId,
                    PageRequest.of(0, localCandidateLimit)
            ));
        }
        if (candidates.isEmpty() && userKey != null && !userKey.isBlank()) {
            candidates.addAll(embeddingMetadataRepository.findByUserKeyAndPrivateModeFalseOrderByCreatedAtDesc(
                    userKey,
                    PageRequest.of(0, localCandidateLimit)
            ));
        }
        if (candidates.isEmpty() && allowGlobalRetrieval) {
            candidates.addAll(embeddingMetadataRepository.findByPrivateModeFalseOrderByCreatedAtDesc(
                    PageRequest.of(0, localCandidateLimit)
            ));
        }
        return candidates;
    }

    private List<EmbeddingMetadata> loadByIds(Collection<Long> embeddingIds) {
        if (embeddingIds == null || embeddingIds.isEmpty()) {
            return List.of();
        }
        return embeddingMetadataRepository.findAllById(embeddingIds).stream()
                .filter(metadata -> !metadata.isPrivateMode())
                .toList();
    }

    private Map<Long, Double> luceneScores(List<LuceneSearchResult> results) {
        if (results.isEmpty()) {
            return Map.of();
        }
        double maxScore = results.stream()
                .mapToDouble(LuceneSearchResult::score)
                .max()
                .orElse(0);
        if (maxScore <= 0) {
            return Map.of();
        }
        return results.stream()
                .collect(Collectors.toMap(
                        LuceneSearchResult::embeddingId,
                        result -> Math.min(1.0, result.score() / maxScore),
                        Math::max,
                        LinkedHashMap::new
                ));
    }

    private VectorSearchResult toSearchResult(
            EmbeddingMetadata metadata,
            List<Double> queryVector,
            String queryText,
            String sessionId,
            RetrievalFilter filter,
            double luceneScore
    ) {
        List<Double> storedVector = fromJson(metadata.getVectorJson());
        double vectorScore = queryVector == null || queryVector.isEmpty() ? 0 : cosineSimilarity(queryVector, storedVector);
        double hybridScore = (vectorScore * 0.72)
                + (luceneScore * 0.36)
                + keywordBoost(queryText, metadata.getContentChunk())
                + factualBoost(queryText, metadata)
                + metadataBoost(filter, metadata.getDocumentType(), metadata.getLanguage(), metadata.getTopic())
                + sessionBoost(sessionId, metadata.getSessionId());
        return new VectorSearchResult(
                metadata.getId(),
                metadata.getDocumentId(),
                metadata.getChunkIndex(),
                metadata.getSourceName(),
                metadata.getSourceType(),
                metadata.getSourceUrl(),
                metadata.getPageNumber(),
                metadata.getSectionTitle(),
                metadata.getDocumentType(),
                metadata.getLanguage(),
                metadata.getTopic(),
                metadata.getContentChunk(),
                hybridScore
        );
    }

    private List<VectorSearchResult> expandNeighbors(
            List<VectorSearchResult> ranked,
            List<Double> queryVector,
            String queryText,
            String sessionId,
            RetrievalFilter filter
    ) {
        if (ranked.isEmpty() || neighborExpansionLimit == 0) {
            return ranked;
        }
        Map<String, VectorSearchResult> expanded = new LinkedHashMap<>();
        int expandedCount = 0;
        for (VectorSearchResult result : ranked) {
            putBest(expanded, result);
            if (result.documentId() == null || result.chunkIndex() == null) {
                continue;
            }
            for (int offset = -neighborExpansionLimit; offset <= neighborExpansionLimit; offset++) {
                if (offset == 0) {
                    continue;
                }
                int neighborIndex = result.chunkIndex() + offset;
                if (neighborIndex < 0) {
                    continue;
                }
                int distance = Math.abs(offset);
                embeddingMetadataRepository.findFirstByDocumentIdAndChunkIndexAndPrivateModeFalse(result.documentId(), neighborIndex)
                        .map(metadata -> toSearchResult(
                                metadata,
                                queryVector == null ? List.of() : queryVector,
                                queryText,
                                sessionId,
                                filter,
                                0
                        ))
                        .map(neighbor -> withScore(neighbor, Math.max(neighbor.score(), result.score() - (distance * 0.06))))
                        .ifPresent(neighbor -> {
                            putBest(expanded, neighbor);
                            log.debug(
                                    "neighbor chunk expanded sourceEmbeddingId={} neighborEmbeddingId={} documentId={} chunkIndex={} neighborIndex={} score={}",
                                    result.embeddingId(),
                                    neighbor.embeddingId(),
                                    result.documentId(),
                                    result.chunkIndex(),
                                    neighbor.chunkIndex(),
                                    round(neighbor.score())
                            );
                        });
                expandedCount++;
            }
        }
        log.info("neighbor expansion completed base={} attempted={} expanded={}", ranked.size(), expandedCount, expanded.size());
        return new ArrayList<>(expanded.values());
    }

    private VectorSearchResult withScore(VectorSearchResult result, double score) {
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

    private void putBest(Map<String, VectorSearchResult> merged, VectorSearchResult candidate) {
        String key = candidate.embeddingId() == null
                ? "hash:" + ContentHash.sha256(candidate.content())
                : "id:" + candidate.embeddingId();
        VectorSearchResult existing = merged.get(key);
        if (existing == null || candidate.score() > existing.score()) {
            merged.put(key, candidate);
        }
    }

    private double sessionBoost(String requestedSessionId, String candidateSessionId) {
        if (requestedSessionId == null || requestedSessionId.isBlank() || candidateSessionId == null) {
            return 0;
        }
        return requestedSessionId.equals(candidateSessionId) ? 0.12 : 0;
    }

    private double metadataBoost(RetrievalFilter filter, String documentType, String language, String topic) {
        if (filter == null || filter.empty()) {
            return 0;
        }
        double boost = 0;
        if (filter.documentTypes().contains(normalizeLabel(documentType))) {
            boost += 0.08;
        }
        if (filter.languages().contains(normalizeLabel(language))) {
            boost += 0.08;
        }
        if (filter.topics().contains(normalizeLabel(topic))) {
            boost += 0.06;
        }
        return boost;
    }

    private double keywordBoost(String queryText, String content) {
        if (queryText == null || queryText.isBlank() || content == null || content.isBlank()) {
            return 0;
        }
        String normalizedContent = content.toLowerCase(Locale.ROOT);
        long matches = tokens(queryText).stream()
                .filter(normalizedContent::contains)
                .count();
        return Math.min(0.40, matches * 0.04);
    }

    private double factualBoost(String queryText, EmbeddingMetadata metadata) {
        String query = normalizeText(queryText);
        if (query.isBlank()) {
            return 0;
        }
        String content = normalizeText(metadata.getContentChunk());
        double boost = 0;
        if (containsAny(query, "degree", "bachelor", "education", "university", "college", "percentage", "year")) {
            if (normalizeLabel(metadata.getSectionTitle()).contains("education")
                    || normalizeLabel(metadata.getTopic()).contains("education")) {
                boost += 0.18;
            }
            if (content.matches(".*\\b(19|20)\\d{2}\\b.*")) {
                boost += 0.08;
            }
            if (containsAny(content, "bachelor", "degree", "university", "college", "education", "percentage", "cgpa")) {
                boost += 0.10;
            }
        }
        Set<String> queryTokens = tokens(queryText);
        if (!queryTokens.isEmpty()) {
            long entityMatches = queryTokens.stream()
                    .filter(token -> token.length() >= 4)
                    .filter(content::contains)
                    .count();
            boost += Math.min(0.16, entityMatches * 0.04);
        }
        return boost;
    }

    private List<Double> fromJson(String json) {
        try {
            return objectMapper.readValue(json, VECTOR_TYPE);
        } catch (JsonProcessingException ex) {
            log.warn("Unable to parse stored embedding vector", ex);
            return List.of();
        }
    }

    private String toJson(List<Double> vector) {
        try {
            return objectMapper.writeValueAsString(vector == null ? List.of() : vector);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize embedding vector", ex);
        }
    }

    private String toJson(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata == null ? Map.of() : metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize chunk metadata", ex);
        }
    }

    private double cosineSimilarity(List<Double> a, List<Double> b) {
        int size = Math.min(a.size(), b.size());
        if (size == 0) {
            return 0;
        }

        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int index = 0; index < size; index++) {
            double left = a.get(index);
            double right = b.get(index);
            dot += left * right;
            normA += left * left;
            normB += right * right;
        }

        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    private boolean hasSearchScope(String sessionId, String userKey, Long activeDocumentId) {
        return activeDocumentId != null
                || (sessionId != null && !sessionId.isBlank())
                || (userKey != null && !userKey.isBlank());
    }

    private String inferDocumentType(String sourceName, String sourceType) {
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        if (name.contains("resume") || name.contains("_cv") || name.endsWith("cv.pdf") || name.endsWith("cv.docx")) {
            return "resume";
        }
        if (name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".properties")
                || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".json")) {
            return "code";
        }
        return defaultIfBlank(sourceType, "knowledge");
    }

    private String inferLanguage(String sourceName) {
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) {
            return "java";
        }
        if (name.endsWith(".xml")) {
            return "xml";
        }
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            return "yaml";
        }
        if (name.endsWith(".properties")) {
            return "properties";
        }
        if (name.endsWith(".json")) {
            return "json";
        }
        return "text";
    }

    private Set<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(text.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(word -> word.replaceAll("[^a-z0-9_./-]", ""))
                .filter(word -> word.length() > 2)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String scoreSummary(List<VectorSearchResult> results) {
        return results.stream()
                .limit(8)
                .map(result -> result.sourceName() + "#" + result.chunkIndex() + ":" + round(result.score()))
                .toList()
                .toString();
    }

    private String scopeLabel(RetrievalScope scope) {
        if (scope.documentScoped()) {
            return "document";
        }
        if (scope.sessionScoped()) {
            return "session";
        }
        if (scope.userScoped()) {
            return "user";
        }
        return "global";
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalizeLabel(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
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
}
