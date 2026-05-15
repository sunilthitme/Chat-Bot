package com.example.internalchatbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;

@Service
public class LlmService {

    private static final String CONTEXT_PROMPT_TEMPLATE = """
            You are an internal help desk chatbot.
            Use the internal answer as the source of truth.
            Rewrite it as a clear, helpful response to the user.

            User question:
            %s

            Internal answer:
            %s
            """;

    private final boolean enabled;
    private final String ollamaUrl;
    private final String model;
    private final double temperature;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public LlmService(
            @Value("${ollama.enabled:false}") boolean enabled,
            @Value("${ollama.url}") String ollamaUrl,
            @Value("${ollama.model}") String model,
            @Value("${ollama.temperature:0.2}") double temperature
    ) {
        this.enabled = enabled;
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.temperature = temperature;
        this.restTemplate = new RestTemplate();
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
                "options", Map.of("temperature", responseTemperature)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                ollamaUrl,
                entity,
                Map.class
        );

        Map responseBody = response.getBody();
        Object generatedResponse = responseBody == null ? null : responseBody.get("response");

        return generatedResponse == null ? "" : generatedResponse.toString();
    }

    public String generateResponseWithContext(String userQuestion, String internalAnswer) {
        String prompt = CONTEXT_PROMPT_TEMPLATE.formatted(userQuestion, internalAnswer);
        return generateResponse(prompt);
    }

    public void streamResponse(String prompt, Consumer<String> onToken) {
        Map<String, Object> request = Map.of(
                "model", model,
                "prompt", prompt,
                "stream", true,
                "options", Map.of("temperature", temperature)
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        restTemplate.execute(ollamaUrl, HttpMethod.POST, clientRequest -> {
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
                        onToken.accept(response.asText());
                    }
                }
            }
            return null;
        });
    }
}
