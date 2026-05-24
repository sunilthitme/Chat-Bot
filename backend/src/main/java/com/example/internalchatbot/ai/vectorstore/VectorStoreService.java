package com.example.internalchatbot.ai.vectorstore;

import com.example.internalchatbot.ai.ingestion.ContentHash;
import com.example.internalchatbot.ai.ingestion.DocumentChunk;
import com.example.internalchatbot.entity.EmbeddingMetadata;
import com.example.internalchatbot.repository.EmbeddingMetadataRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);
    private static final TypeReference<List<Double>> VECTOR_TYPE = new TypeReference<>() {
    };

    private final EmbeddingMetadataRepository embeddingMetadataRepository;
    private final ObjectMapper objectMapper;
    private final ChromaEmbeddingStoreProvider chromaEmbeddingStoreProvider;
    private final int localCandidateLimit;

    public VectorStoreService(
            EmbeddingMetadataRepository embeddingMetadataRepository,
            ChromaEmbeddingStoreProvider chromaEmbeddingStoreProvider,
            @Value("${rag.local-candidate-limit:600}") int localCandidateLimit
    ) {
        this.embeddingMetadataRepository = embeddingMetadataRepository;
        this.chromaEmbeddingStoreProvider = chromaEmbeddingStoreProvider;
        this.objectMapper = new ObjectMapper();
        this.localCandidateLimit = Math.max(50, localCandidateLimit);
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
        return store(namespace, sessionId, documentId, chunk, vector, privateMode);
    }

    public EmbeddingMetadata store(
            String namespace,
            String sessionId,
            Long documentId,
            DocumentChunk chunk,
            List<Double> vector,
            boolean privateMode
    ) {
        if (privateMode) {
            throw new IllegalArgumentException("Private-mode content must not be stored in the vector database");
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
        syncToChroma(saved, vector);
        return saved;
    }

    public List<VectorSearchResult> search(List<Double> queryVector, int topK) {
        return search(null, "", queryVector, topK, RetrievalFilter.none());
    }

    public List<VectorSearchResult> search(String queryText, List<Double> queryVector, int topK) {
        return search(null, queryText, queryVector, topK, RetrievalFilter.none());
    }

    public List<VectorSearchResult> search(
            String sessionId,
            String queryText,
            List<Double> queryVector,
            int topK,
            RetrievalFilter filter
    ) {
        if (queryVector.isEmpty()) {
            return List.of();
        }

        int candidateCount = Math.max(topK * 8, topK);
        Map<String, VectorSearchResult> merged = new LinkedHashMap<>();
        for (VectorSearchResult result : searchChroma(queryVector, candidateCount)) {
            putBest(merged, applySearchBoosts(result, sessionId, queryText, filter));
        }
        for (EmbeddingMetadata metadata : localCandidates(sessionId)) {
            putBest(merged, toSearchResult(metadata, queryVector, queryText, sessionId, filter));
        }

        return merged.values()
                .stream()
                .sorted(Comparator.comparingDouble(VectorSearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    public void deleteByDocumentId(Long documentId) {
        List<EmbeddingMetadata> embeddings = embeddingMetadataRepository.findByDocumentIdAndPrivateModeFalse(documentId);
        List<String> chromaIds = embeddings.stream()
                .map(metadata -> "embedding-" + metadata.getId())
                .toList();
        chromaEmbeddingStoreProvider.getStore().ifPresent(embeddingStore -> {
            try {
                embeddingStore.removeAll(chromaIds);
            } catch (RuntimeException ex) {
                chromaEmbeddingStoreProvider.markUnavailable(ex);
                log.warn("ChromaDB delete failed for documentId={}. Local metadata will still be deleted.", documentId, ex);
            }
        });
        embeddingMetadataRepository.deleteAll(embeddings);
    }

    private List<EmbeddingMetadata> localCandidates(String sessionId) {
        List<EmbeddingMetadata> candidates = new ArrayList<>();
        if (sessionId != null && !sessionId.isBlank()) {
            candidates.addAll(embeddingMetadataRepository.findBySessionIdAndPrivateModeFalseOrderByCreatedAtDesc(
                    sessionId,
                    PageRequest.of(0, localCandidateLimit)
            ));
        }
        candidates.addAll(embeddingMetadataRepository.findByPrivateModeFalseOrderByCreatedAtDesc(
                PageRequest.of(0, localCandidateLimit)
        ));
        return candidates;
    }

    private VectorSearchResult toSearchResult(
            EmbeddingMetadata metadata,
            List<Double> queryVector,
            String queryText,
            String sessionId,
            RetrievalFilter filter
    ) {
        List<Double> storedVector = fromJson(metadata.getVectorJson());
        double vectorScore = cosineSimilarity(queryVector, storedVector);
        double hybridScore = vectorScore
                + keywordBoost(queryText, metadata.getContentChunk())
                + metadataBoost(filter, metadata.getDocumentType(), metadata.getLanguage(), metadata.getTopic())
                + sessionBoost(sessionId, metadata.getSessionId());
        return new VectorSearchResult(
                metadata.getId(),
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

    private VectorSearchResult applySearchBoosts(
            VectorSearchResult result,
            String sessionId,
            String queryText,
            RetrievalFilter filter
    ) {
        double score = result.score()
                + keywordBoost(queryText, result.content())
                + metadataBoost(filter, result.documentType(), result.language(), result.topic());
        return new VectorSearchResult(
                result.embeddingId(),
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

    private void syncToChroma(EmbeddingMetadata metadata, List<Double> vector) {
        if (vector.isEmpty()) {
            return;
        }

        chromaEmbeddingStoreProvider.getStore().ifPresent(embeddingStore -> {
            try {
                embeddingStore.addAll(
                        List.of("embedding-" + metadata.getId()),
                        List.of(toEmbedding(vector)),
                        List.of(toTextSegment(metadata))
                );
            } catch (RuntimeException ex) {
                chromaEmbeddingStoreProvider.markUnavailable(ex);
                log.warn("ChromaDB sync failed. Local vector metadata remains available.", ex);
            }
        });
    }

    private List<VectorSearchResult> searchChroma(List<Double> queryVector, int topK) {
        return chromaEmbeddingStoreProvider.getStore()
                .map(embeddingStore -> {
                    try {
                        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                                .queryEmbedding(toEmbedding(queryVector))
                                .maxResults(topK)
                                .build();
                        return embeddingStore.search(request).matches()
                                .stream()
                                .map(this::toSearchResult)
                                .toList();
                    } catch (RuntimeException ex) {
                        chromaEmbeddingStoreProvider.markUnavailable(ex);
                        log.warn("ChromaDB search failed. Falling back to local vector metadata.", ex);
                        return List.<VectorSearchResult>of();
                    }
                })
                .orElse(List.of());
    }

    private VectorSearchResult toSearchResult(EmbeddingMatch<TextSegment> match) {
        TextSegment textSegment = match.embedded();
        Metadata metadata = textSegment.metadata();
        return new VectorSearchResult(
                readEmbeddingId(match.embeddingId(), metadata),
                defaultIfBlank(metadata.getString("sourceName"), "chroma"),
                defaultIfBlank(metadata.getString("sourceType"), "knowledge"),
                metadata.getString("sourceUrl"),
                metadata.getInteger("pageNumber"),
                metadata.getString("sectionTitle"),
                metadata.getString("documentType"),
                metadata.getString("language"),
                metadata.getString("topic"),
                textSegment.text(),
                match.score() == null ? 0 : match.score()
        );
    }

    private TextSegment toTextSegment(EmbeddingMetadata metadata) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("sourceName", metadata.getSourceName());
        values.put("sourceType", metadata.getSourceType());
        values.put("embeddingId", metadata.getId());
        putIfPresent(values, "sessionId", metadata.getSessionId());
        putIfPresent(values, "sourceUrl", metadata.getSourceUrl());
        putIfPresent(values, "pageNumber", metadata.getPageNumber());
        putIfPresent(values, "sectionTitle", metadata.getSectionTitle());
        putIfPresent(values, "documentType", metadata.getDocumentType());
        putIfPresent(values, "language", metadata.getLanguage());
        putIfPresent(values, "topic", metadata.getTopic());
        putIfPresent(values, "chunkIndex", metadata.getChunkIndex());
        putIfPresent(values, "contentHash", metadata.getContentHash());
        putIfPresent(values, "uploadDate", metadata.getCreatedAt() == null ? null : metadata.getCreatedAt().toString());
        return TextSegment.from(
                metadata.getContentChunk(),
                Metadata.from(values)
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

    private Embedding toEmbedding(List<Double> vector) {
        return Embedding.from(vector.stream()
                .map(Double::floatValue)
                .toList());
    }

    private Long readEmbeddingId(String embeddingId, Metadata metadata) {
        Long metadataId = metadata.getLong("embeddingId");
        if (metadataId != null) {
            return metadataId;
        }
        if (embeddingId != null && embeddingId.startsWith("embedding-")) {
            try {
                return Long.parseLong(embeddingId.substring("embedding-".length()));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
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
        long matches = java.util.Arrays.stream(queryText.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(word -> word.replaceAll("[^a-z0-9_./-]", ""))
                .filter(word -> word.length() > 2)
                .distinct()
                .filter(normalizedContent::contains)
                .count();
        return Math.min(0.40, matches * 0.04);
    }

    private String toJson(List<Double> vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize embedding vector", ex);
        }
    }

    private String toJson(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize chunk metadata", ex);
        }
    }

    private List<Double> fromJson(String json) {
        try {
            return objectMapper.readValue(json, VECTOR_TYPE);
        } catch (JsonProcessingException ex) {
            log.warn("Unable to parse stored embedding vector", ex);
            return List.of();
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

    private String inferDocumentType(String sourceName, String sourceType) {
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
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

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalizeLabel(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private void putIfPresent(Map<String, Object> values, String key, Object value) {
        if (value != null && !(value instanceof String string && string.isBlank())) {
            values.put(key, value);
        }
    }
}
