package com.example.internalchatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ollama")
public record OllamaProperties(
        boolean enabled,
        String baseUrl,
        String embeddingModel,
        Duration embeddingTimeout
) {
    public OllamaProperties {
        baseUrl = defaultIfBlank(baseUrl, "http://localhost:11434").replaceAll("/+$", "");
        embeddingModel = defaultIfBlank(embeddingModel, "nomic-embed-text");
        embeddingTimeout = embeddingTimeout == null ? Duration.ofSeconds(45) : embeddingTimeout;
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
