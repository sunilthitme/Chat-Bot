package com.example.internalchatbot.service;

import com.example.internalchatbot.config.OllamaProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EmbeddingService {

    private final boolean enabled;
    private final OllamaEmbeddingModel embeddingModel;

    public EmbeddingService(OllamaProperties properties, OllamaEmbeddingModel embeddingModel) {
        this.enabled = properties.enabled();
        this.embeddingModel = embeddingModel;
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
