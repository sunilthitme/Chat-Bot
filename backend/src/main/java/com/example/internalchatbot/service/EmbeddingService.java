package com.example.internalchatbot.service;

import com.example.internalchatbot.config.OllamaProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EmbeddingService {

    private final boolean enabled;
    private final OllamaEmbeddingModel embeddingModel;
    private final int batchSize;
    private final Map<String, List<Double>> cache;

    public EmbeddingService(
            OllamaProperties properties,
            OllamaEmbeddingModel embeddingModel,
            @Value("${rag.embedding-batch-size:12}") int batchSize,
            @Value("${rag.embedding-cache-size:512}") int cacheSize
    ) {
        this.enabled = properties.enabled();
        this.embeddingModel = embeddingModel;
        this.batchSize = Math.max(1, batchSize);
        this.cache = Collections.synchronizedMap(new LinkedHashMap<>(cacheSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Double>> eldest) {
                return size() > Math.max(0, cacheSize);
            }
        });
    }

    public List<Double> embed(String text) {
        if (!enabled) {
            return List.of();
        }
        String key = ContentHash.sha256(text);
        List<Double> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        Embedding embedding = embeddingModel.embed(text).content();
        List<Double> vector = toDoubleVector(embedding);
        cache.put(key, vector);
        return vector;
    }

    public List<List<Double>> embedAll(List<String> texts) {
        if (!enabled || texts.isEmpty()) {
            return List.of();
        }

        List<List<Double>> vectors = new ArrayList<>(Collections.nCopies(texts.size(), List.of()));
        List<Integer> pendingIndexes = new ArrayList<>();
        List<TextSegment> pendingSegments = new ArrayList<>();

        for (int index = 0; index < texts.size(); index++) {
            String text = texts.get(index);
            String key = ContentHash.sha256(text);
            List<Double> cached = cache.get(key);
            if (cached != null) {
                vectors.set(index, cached);
                continue;
            }
            pendingIndexes.add(index);
            pendingSegments.add(TextSegment.from(text));

            if (pendingSegments.size() >= batchSize) {
                embedPending(texts, vectors, pendingIndexes, pendingSegments);
            }
        }

        embedPending(texts, vectors, pendingIndexes, pendingSegments);
        return vectors;
    }

    private void embedPending(
            List<String> texts,
            List<List<Double>> vectors,
            List<Integer> pendingIndexes,
            List<TextSegment> pendingSegments
    ) {
        if (pendingSegments.isEmpty()) {
            return;
        }

        List<Embedding> embeddings = embeddingModel.embedAll(List.copyOf(pendingSegments)).content();
        for (int offset = 0; offset < embeddings.size(); offset++) {
            int originalIndex = pendingIndexes.get(offset);
            List<Double> vector = toDoubleVector(embeddings.get(offset));
            vectors.set(originalIndex, vector);
            cache.put(ContentHash.sha256(texts.get(originalIndex)), vector);
        }
        pendingIndexes.clear();
        pendingSegments.clear();
    }

    private List<Double> toDoubleVector(Embedding embedding) {
        return embedding.vectorAsList().stream()
                .map(Float::doubleValue)
                .toList();
    }
}
