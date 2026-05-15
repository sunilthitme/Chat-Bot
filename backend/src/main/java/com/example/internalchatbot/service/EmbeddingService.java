package com.example.internalchatbot.service;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

@Service
public class EmbeddingService {

    private final boolean enabled;
    private final OllamaEmbeddingModel embeddingModel;

    public EmbeddingService(
            @Value("${ollama.enabled:false}") boolean enabled,
            @Value("${ollama.base-url}") String ollamaBaseUrl,
            @Value("${ollama.embedding-model}") String embeddingModel
    ) {
        this.enabled = enabled;
        this.embeddingModel = OllamaEmbeddingModel.builder()
                .baseUrl(ollamaBaseUrl)
                .modelName(embeddingModel)
                .timeout(Duration.ofSeconds(60))
                .build();
    }

    public List<Double> embed(String text) {
        if (!enabled) {
            return List.of();
        }

        Embedding embedding = embeddingModel.embed(text).content();
        return embedding.vectorAsList().stream()
                .map(Float::doubleValue)
                .toList();
    }
}
