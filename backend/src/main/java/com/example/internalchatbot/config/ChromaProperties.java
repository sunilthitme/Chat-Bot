package com.example.internalchatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "chroma")
public record ChromaProperties(
        boolean enabled,
        String baseUrl,
        String collectionName,
        String tenantName,
        String databaseName,
        Duration timeout,
        Duration healthCheckTimeout,
        int healthCheckRetries,
        Duration retryDelay,
        boolean logRequests,
        boolean logResponses
) {
    public ChromaProperties {
        baseUrl = defaultIfBlank(baseUrl, "http://localhost:8000").replaceAll("/+$", "");
        collectionName = defaultIfBlank(collectionName, "internal_chatbot_knowledge");
        tenantName = defaultIfBlank(tenantName, "default");
        databaseName = defaultIfBlank(databaseName, "default");
        timeout = timeout == null ? Duration.ofSeconds(10) : timeout;
        healthCheckTimeout = healthCheckTimeout == null ? Duration.ofSeconds(2) : healthCheckTimeout;
        healthCheckRetries = healthCheckRetries <= 0 ? 3 : healthCheckRetries;
        retryDelay = retryDelay == null ? Duration.ofSeconds(10) : retryDelay;
    }

    public String heartbeatUrl() {
        return baseUrl + "/api/v2/heartbeat";
    }

    public List<String> candidateBaseUrls() {
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(baseUrl);

        URI uri = URI.create(baseUrl);
        String host = uri.getHost();
        if (host != null && isLoopback(host)) {
            addCandidate(candidates, uri, "localhost");
            addCandidate(candidates, uri, "127.0.0.1");
            addCandidate(candidates, uri, "::1");
        }

        return List.copyOf(candidates);
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static void addCandidate(Set<String> candidates, URI sourceUri, String host) {
        try {
            URI uri = new URI(
                    sourceUri.getScheme(),
                    sourceUri.getUserInfo(),
                    host,
                    sourceUri.getPort(),
                    sourceUri.getPath(),
                    sourceUri.getQuery(),
                    sourceUri.getFragment()
            );
            candidates.add(uri.toString().replaceAll("/+$", ""));
        } catch (URISyntaxException ignored) {
            // Keep the configured base URL if Java cannot build an alternate loopback URI.
        }
    }
}
