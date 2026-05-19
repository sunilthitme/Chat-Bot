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
        Duration connectTimeout,
        Duration requestTimeout,
        double temperature
) {
    public OllamaProperties {
        baseUrl = defaultIfBlank(baseUrl, "http://localhost:11434").replaceAll("/+$", "");
        url = defaultIfBlank(url, baseUrl + "/api/generate");
        model = defaultIfBlank(model, "llama3.2");
        embeddingUrl = defaultIfBlank(embeddingUrl, baseUrl + "/api/embeddings");
        embeddingModel = defaultIfBlank(embeddingModel, "nomic-embed-text");
        embeddingTimeout = embeddingTimeout == null ? Duration.ofSeconds(60) : embeddingTimeout;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(90) : requestTimeout;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
