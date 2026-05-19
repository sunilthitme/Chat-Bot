package com.example.internalchatbot.ai.vectorstore;

import com.example.internalchatbot.config.ChromaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class ChromaHealthClient {

    private static final Logger log = LoggerFactory.getLogger(ChromaHealthClient.class);

    private final ChromaProperties properties;
    private final RestTemplate restTemplate;
    private volatile String healthyBaseUrl;

    public ChromaHealthClient(ChromaProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.healthCheckTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.healthCheckTimeout().toMillis());
        this.restTemplate = new RestTemplate(requestFactory);
    }

    public boolean isHealthy() {
        if (!properties.enabled()) {
            return false;
        }

        for (int attempt = 1; attempt <= properties.healthCheckRetries(); attempt++) {
            for (String baseUrl : properties.candidateBaseUrls()) {
                if (checkHeartbeat(baseUrl, attempt)) {
                    healthyBaseUrl = baseUrl;
                    return true;
                }
            }
            sleepBeforeRetry(attempt);
        }

        return false;
    }

    public String baseUrlForClient() {
        return healthyBaseUrl == null ? properties.baseUrl() : healthyBaseUrl;
    }

    private boolean checkHeartbeat(String baseUrl, int attempt) {
        String heartbeatUrl = baseUrl + "/api/v2/heartbeat";
        try {
            ResponseEntity<String> response = restTemplate.getForEntity(heartbeatUrl, String.class);
            if (response.getStatusCode().is2xxSuccessful()) {
                return true;
            }
            log.warn("Chroma heartbeat returned status={} attempt={}/{} url={}",
                    response.getStatusCode(), attempt, properties.healthCheckRetries(), heartbeatUrl);
        } catch (RestClientException ex) {
            log.warn("Chroma heartbeat failed attempt={}/{} url={} reason={}",
                    attempt, properties.healthCheckRetries(), heartbeatUrl, ex.getMessage());
        }
        return false;
    }

    private void sleepBeforeRetry(int attempt) {
        if (attempt >= properties.healthCheckRetries()) {
            return;
        }
        try {
            Thread.sleep(Math.min(properties.retryDelay().toMillis(), 2_000));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
