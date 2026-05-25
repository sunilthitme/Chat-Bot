package com.example.internalchatbot.ai.llm.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class GitHubModelsService {

    private static final Logger log = LoggerFactory.getLogger(GitHubModelsService.class);

    private final GitHubModelsProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GitHubModelsService(GitHubModelsProperties properties) {
        this.properties = properties;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(properties.connectTimeout());
        requestFactory.setReadTimeout(properties.requestTimeout());
        this.restTemplate = new RestTemplate(requestFactory);
        this.objectMapper = new ObjectMapper();
    }

    public String complete(String prompt, double temperature) {
        GitHubModelsChatRequest request = request(prompt, temperature, false);
        HttpEntity<GitHubModelsChatRequest> entity = new HttpEntity<>(request, headers());
        ResponseEntity<GitHubModelsChatResponse> response = executeWithRetry(() -> restTemplate.postForEntity(
                properties.endpoint(),
                entity,
                GitHubModelsChatResponse.class
        ));
        GitHubModelsChatResponse body = response.getBody();
        return body == null ? "" : body.firstMessageContent();
    }

    public void stream(String prompt, double temperature, Consumer<String> onToken) {
        GitHubModelsChatRequest request = request(prompt, temperature, true);
        HttpHeaders headers = headers();
        AtomicBoolean tokenEmitted = new AtomicBoolean(false);

        executeWithRetry(() -> restTemplate.execute(properties.endpoint(), HttpMethod.POST, clientRequest -> {
            clientRequest.getHeaders().putAll(headers);
            objectMapper.writeValue(clientRequest.getBody(), request);
        }, clientResponse -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(clientResponse.getBody(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || !line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring("data:".length()).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    String token = tokenFromSsePayload(data);
                    if (!token.isBlank()) {
                        tokenEmitted.set(true);
                        onToken.accept(token);
                    }
                }
            }
            return null;
        }), tokenEmitted);
    }

    private GitHubModelsChatRequest request(String prompt, double temperature, boolean stream) {
        return new GitHubModelsChatRequest(
                properties.model(),
                List.of(new GitHubModelsMessage("user", prompt)),
                clamp(temperature, 0, 1),
                properties.maxTokens(),
                stream
        );
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(properties.token().trim());
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.valueOf("application/vnd.github+json")));
        headers.set("X-GitHub-Api-Version", properties.apiVersion());
        return headers;
    }

    private String tokenFromSsePayload(String data) {
        try {
            JsonNode node = objectMapper.readTree(data);
            JsonNode error = node.get("error");
            if (error != null) {
                throw new RestClientException("GitHub Models streaming error: " + error);
            }
            JsonNode deltaContent = node.at("/choices/0/delta/content");
            if (!deltaContent.isMissingNode() && !deltaContent.isNull()) {
                return deltaContent.asText("");
            }
            JsonNode messageContent = node.at("/choices/0/message/content");
            return messageContent.isMissingNode() || messageContent.isNull() ? "" : messageContent.asText("");
        } catch (RestClientException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new RestClientException("Unable to parse GitHub Models streaming payload", ex);
        }
    }

    private <T> T executeWithRetry(GitHubModelsCall<T> call) {
        return executeWithRetry(call, new AtomicBoolean(false));
    }

    private <T> T executeWithRetry(GitHubModelsCall<T> call, AtomicBoolean tokenEmitted) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= properties.retryAttempts(); attempt++) {
            try {
                return call.execute();
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (tokenEmitted.get() || attempt >= properties.retryAttempts()) {
                    throw ex;
                }
                Duration backoff = properties.retryBackoff().multipliedBy(attempt);
                log.warn(
                        "GitHub Models request failed attempt={} retryingAfterMs={} endpoint={} model={}",
                        attempt,
                        backoff.toMillis(),
                        properties.endpoint(),
                        properties.model(),
                        ex
                );
                sleep(backoff);
            }
        }
        throw lastFailure == null ? new RestClientException("GitHub Models request failed") : lastFailure;
    }

    private void sleep(Duration backoff) {
        try {
            Thread.sleep(backoff.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    @FunctionalInterface
    private interface GitHubModelsCall<T> {
        T execute();
    }
}
