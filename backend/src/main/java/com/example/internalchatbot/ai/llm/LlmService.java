package com.example.internalchatbot.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@Service
public class LlmService {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    private final boolean enabled;
    private final String ollamaUrl;
    private final String model;
    private final double temperature;
    private final int numPredict;
    private final int numContext;
    private final int retryAttempts;
    private final Duration retryBackoff;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public LlmService(
            @Value("${ollama.enabled:false}") boolean enabled,
            @Value("${ollama.url}") String ollamaUrl,
            @Value("${ollama.model}") String model,
            @Value("${ollama.temperature:0.2}") double temperature,
            @Value("${ollama.num-predict:512}") int numPredict,
            @Value("${ollama.num-ctx:4096}") int numContext,
            @Value("${ollama.connect-timeout:5s}") Duration connectTimeout,
            @Value("${ollama.request-timeout:90s}") Duration requestTimeout,
            @Value("${ollama.retry-attempts:2}") int retryAttempts,
            @Value("${ollama.retry-backoff:500ms}") Duration retryBackoff
    ) {
        this.enabled = enabled;
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.temperature = temperature;
        this.numPredict = Math.max(128, numPredict);
        this.numContext = Math.max(2048, numContext);
        this.retryAttempts = Math.max(1, retryAttempts);
        this.retryBackoff = retryBackoff == null ? Duration.ofMillis(500) : retryBackoff;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(requestTimeout);
        this.restTemplate = new RestTemplate(requestFactory);
        this.objectMapper = new ObjectMapper();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String generateResponse(String prompt) {
        return generateResponse(prompt, temperature);
    }

    public String generateResponse(String prompt, double responseTemperature) {
        Map<String, Object> request = Map.of(
                "model", model,
                "prompt", prompt,
                "stream", false,
                "options", options(responseTemperature)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        ResponseEntity<Map> response = executeWithRetry(() -> restTemplate.postForEntity(
                ollamaUrl,
                entity,
                Map.class
        ));

        Map responseBody = response.getBody();
        Object generatedResponse = responseBody == null ? null : responseBody.get("response");

        return generatedResponse == null ? "" : generatedResponse.toString();
    }

    public void streamResponse(String prompt, Consumer<String> onToken) {
        Map<String, Object> request = Map.of(
                "model", model,
                "prompt", prompt,
                "stream", true,
                "options", options(temperature)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);
        AtomicBoolean tokenEmitted = new AtomicBoolean(false);

        executeWithRetry(() -> restTemplate.execute(ollamaUrl, HttpMethod.POST, clientRequest -> {
            clientRequest.getHeaders().putAll(headers);
            objectMapper.writeValue(clientRequest.getBody(), entity.getBody());
        }, clientResponse -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(clientResponse.getBody(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode node = objectMapper.readTree(line);
                    JsonNode response = node.get("response");
                    if (response != null && !response.asText().isBlank()) {
                        tokenEmitted.set(true);
                        onToken.accept(response.asText());
                    }
                }
            }
            return null;
        }), tokenEmitted);
    }

    private <T> T executeWithRetry(LlmCall<T> call) {
        return executeWithRetry(call, new AtomicBoolean(false));
    }

    private <T> T executeWithRetry(LlmCall<T> call, AtomicBoolean tokenEmitted) {
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= retryAttempts; attempt++) {
            try {
                return call.execute();
            } catch (RuntimeException ex) {
                lastFailure = ex;
                if (tokenEmitted.get() || attempt >= retryAttempts) {
                    throw ex;
                }
                log.warn("Ollama request failed attempt={} retryingAfterMs={}", attempt, retryBackoff.toMillis(), ex);
                sleepBeforeRetry(attempt);
            }
        }
        throw lastFailure == null ? new RestClientException("Ollama request failed") : lastFailure;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(retryBackoff.toMillis() * attempt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, Object> options(double responseTemperature) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", responseTemperature);
        options.put("num_predict", numPredict);
        options.put("num_ctx", numContext);
        options.put("top_k", 30);
        options.put("top_p", 0.9);
        return options;
    }

    @FunctionalInterface
    private interface LlmCall<T> {
        T execute();
    }
}
