package com.example.internalchatbot.ai.llm.github;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "ai.github")
public record GitHubModelsProperties(
        Boolean enabled,
        String token,
        String endpoint,
        String model,
        String apiVersion,
        double temperature,
        int maxTokens,
        Duration connectTimeout,
        Duration requestTimeout,
        int retryAttempts,
        Duration retryBackoff
) {

    public GitHubModelsProperties {
        endpoint = defaultIfBlank(endpoint, "https://models.github.ai/inference/chat/completions");
        model = defaultIfBlank(model, "openai/gpt-4o-mini");
        apiVersion = defaultIfBlank(apiVersion, "2026-03-10");
        temperature = clamp(temperature <= 0 ? 0.2 : temperature, 0, 1);
        maxTokens = Math.max(128, maxTokens <= 0 ? 1000 : maxTokens);
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(120) : requestTimeout;
        retryAttempts = Math.max(1, retryAttempts <= 0 ? 2 : retryAttempts);
        retryBackoff = retryBackoff == null ? Duration.ofMillis(500) : retryBackoff;
    }

    public boolean providerEnabled() {
        return (enabled == null || enabled) && token != null && !token.isBlank();
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
