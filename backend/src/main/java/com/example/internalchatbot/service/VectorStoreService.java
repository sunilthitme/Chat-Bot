package com.example.internalchatbot.service;

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
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);
    private static final TypeReference<List<Double>> VECTOR_TYPE = new TypeReference<>() {
    };

    private final EmbeddingMetadataRepository embeddingMetadataRepository;
    private final ObjectMapper objectMapper;
    private final ChromaEmbeddingStoreProvider chromaEmbeddingStoreProvider;

    public VectorStoreService(
            EmbeddingMetadataRepository embeddingMetadataRepository,
            ChromaEmbeddingStoreProvider chromaEmbeddingStoreProvider
    ) {
        this.embeddingMetadataRepository = embeddingMetadataRepository;
        this.chromaEmbeddingStoreProvider = chromaEmbeddingStoreProvider;
        this.objectMapper = new ObjectMapper();
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
        if (privateMode) {
            throw new IllegalArgumentException("Private-mode content must not be stored in the vector database");
        }

        EmbeddingMetadata metadata = new EmbeddingMetadata();
        metadata.setNamespace(namespace);
        metadata.setSessionId(sessionId);
        metadata.setDocumentId(documentId);
        metadata.setSourceName(sourceName);
        metadata.setSourceType(sourceType);
        metadata.setContentChunk(content);
        metadata.setVectorJson(toJson(vector));
        metadata.setPrivateMode(false);

        EmbeddingMetadata saved = embeddingMetadataRepository.save(metadata);
        syncToChroma(saved, vector);
        return saved;
    }

    public List<VectorSearchResult> search(List<Double> queryVector, int topK) {
        if (queryVector.isEmpty()) {
            return List.of();
        }

        List<VectorSearchResult> chromaResults = searchChroma(queryVector, topK);
        if (!chromaResults.isEmpty()) {
            return chromaResults;
        }

        return embeddingMetadataRepository
                .findTop300ByPrivateModeFalseOrderByCreatedAtDesc()
                .stream()
                .map(metadata -> toSearchResult(metadata, queryVector))
                .sorted(Comparator.comparingDouble(VectorSearchResult::score).reversed())
                .limit(topK)
                .toList();
    }

    private VectorSearchResult toSearchResult(EmbeddingMetadata metadata, List<Double> queryVector) {
        List<Double> storedVector = fromJson(metadata.getVectorJson());
        return new VectorSearchResult(
                metadata.getId(),
                metadata.getSourceName(),
                metadata.getSourceType(),
                metadata.getContentChunk(),
                cosineSimilarity(queryVector, storedVector)
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
                textSegment.text(),
                match.score() == null ? 0 : match.score()
        );
    }

    private TextSegment toTextSegment(EmbeddingMetadata metadata) {
        return TextSegment.from(
                metadata.getContentChunk(),
                Metadata.from(Map.of(
                        "sourceName", metadata.getSourceName(),
                        "sourceType", metadata.getSourceType(),
                        "embeddingId", metadata.getId()
                ))
        );
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

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String toJson(List<Double> vector) {
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize embedding vector", ex);
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
}
