package com.example.internalchatbot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@Service
public class LlmService {

    private final boolean enabled;
    private final String ollamaUrl;
    private final String model;
    private final RestTemplate restTemplate;

    public LlmService(
            @Value("${ollama.enabled:false}") boolean enabled,
            @Value("${ollama.url}") String ollamaUrl,
            @Value("${ollama.model}") String model
    ) {
        this.enabled = enabled;
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.restTemplate = new RestTemplate();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String generateResponse(String prompt) {
        Map<String, Object> request = Map.of(
                "model", model,
                "prompt", prompt,
                "stream", false
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
}
