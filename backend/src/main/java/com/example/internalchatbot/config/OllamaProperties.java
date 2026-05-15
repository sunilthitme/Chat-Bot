package com.example.internalchatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ollama")
public record OllamaProperties(
        boolean enabled,
        String baseUrl,
        String url,
        String model,
        String embeddingUrl,
        String embeddingModel,
        Duration embeddingTimeout,
        double temperature
) {
    public OllamaProperties {
        baseUrl = defaultIfBlank(baseUrl, "http://localhost:11434").replaceAll("/+$", "");
        url = defaultIfBlank(url, baseUrl + "/api/generate");
        model = defaultIfBlank(model, "llama3.2");
        embeddingUrl = defaultIfBlank(embeddingUrl, baseUrl + "/api/embeddings");
        embeddingModel = defaultIfBlank(embeddingModel, "nomic-embed-text");
        embeddingTimeout = embeddingTimeout == null ? Duration.ofSeconds(60) : embeddingTimeout;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
