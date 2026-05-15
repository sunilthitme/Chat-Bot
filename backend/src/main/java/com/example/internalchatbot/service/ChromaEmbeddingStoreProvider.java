package com.example.internalchatbot.service;

import com.example.internalchatbot.config.ChromaProperties;
import dev.langchain4j.store.embedding.chroma.ChromaApiVersion;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

@Component
public class ChromaEmbeddingStoreProvider {

    private static final Logger log = LoggerFactory.getLogger(ChromaEmbeddingStoreProvider.class);

    private final ChromaProperties properties;
    private final ChromaHealthClient healthClient;

    private volatile ChromaEmbeddingStore embeddingStore;
    private volatile Instant nextRetryAt = Instant.MIN;

    public ChromaEmbeddingStoreProvider(ChromaProperties properties, ChromaHealthClient healthClient) {
        this.properties = properties;
        this.healthClient = healthClient;
    }

    public Optional<ChromaEmbeddingStore> getStore() {
        if (!properties.enabled()) {
            return Optional.empty();
        }

        ChromaEmbeddingStore currentStore = embeddingStore;
        if (currentStore != null) {
            return Optional.of(currentStore);
        }

        if (Instant.now().isBefore(nextRetryAt)) {
            return Optional.empty();
        }

        synchronized (this) {
            if (embeddingStore != null) {
                return Optional.of(embeddingStore);
            }
            if (!healthClient.isHealthy()) {
                scheduleRetry("Chroma heartbeat did not pass");
                return Optional.empty();
            }

            try {
                embeddingStore = ChromaEmbeddingStore.builder()
                        .apiVersion(ChromaApiVersion.V2)
                        .baseUrl(healthClient.baseUrlForClient())
                        .tenantName(properties.tenantName())
                        .databaseName(properties.databaseName())
                        .collectionName(properties.collectionName())
                        .timeout(properties.timeout())
                        .logRequests(properties.logRequests())
                        .logResponses(properties.logResponses())
                        .build();
                log.info("Chroma V2 embedding store initialized collection={} tenant={} database={}",
                        properties.collectionName(), properties.tenantName(), properties.databaseName());
                return Optional.of(embeddingStore);
            } catch (RuntimeException ex) {
                scheduleRetry("Chroma embedding store initialization failed: " + ex.getMessage());
                log.warn("Chroma embedding store unavailable. Local vector fallback remains active.", ex);
                return Optional.empty();
            }
        }
    }

    public synchronized void markUnavailable(RuntimeException ex) {
        embeddingStore = null;
        scheduleRetry("Chroma request failed: " + ex.getMessage());
    }

    private void scheduleRetry(String reason) {
        nextRetryAt = Instant.now().plus(properties.retryDelay());
        log.warn("{}; next Chroma retry after {}", reason, nextRetryAt);
    }
}
