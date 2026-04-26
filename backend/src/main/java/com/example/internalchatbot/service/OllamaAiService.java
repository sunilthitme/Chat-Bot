package com.example.internalchatbot.service;

import com.example.internalchatbot.entity.ChatQuestion;
import com.example.internalchatbot.repository.ChatQuestionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

// Service calls a free local Ollama model when the database does not have a direct answer.
@Service
public class OllamaAiService {

    private static final Logger log = LoggerFactory.getLogger(OllamaAiService.class);

    private final ChatQuestionRepository chatQuestionRepository;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${chatbot.ai.enabled:true}")
    private boolean aiEnabled;

    @Value("${chatbot.ai.ollama-url:http://localhost:11434/api/generate}")
    private String ollamaUrl;

    @Value("${chatbot.ai.model:llama3.1}")
    private String model;

    @Value("${chatbot.ai.timeout-seconds:20}")
    private long timeoutSeconds;

    public OllamaAiService(ChatQuestionRepository chatQuestionRepository, ObjectMapper objectMapper) {
        this.chatQuestionRepository = chatQuestionRepository;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String generateReply(String question) {
        if (!aiEnabled) {
            log.info("Ollama AI fallback is disabled");
            return "";
        }

        try {
            String prompt = buildPrompt(question);
            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model", model,
                    "prompt", prompt,
                    "stream", false
            ));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ollamaUrl))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            log.info("Sending chatbot fallback request to Ollama. model={}", model);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Ollama returned non-success status. statusCode={}", response.statusCode());
                return "";
            }

            JsonNode jsonNode = objectMapper.readTree(response.body());
            String aiReply = jsonNode.path("response").asText("").trim();
            log.info("Ollama fallback response received. hasReply={}", !aiReply.isBlank());
            return aiReply;
        } catch (Exception exception) {
            log.error("Ollama fallback failed", exception);
            return "";
        }
    }

    private String buildPrompt(String question) {
        List<ChatQuestion> knowledgeBase = chatQuestionRepository.findAll();
        StringBuilder contextBuilder = new StringBuilder();

        for (ChatQuestion item : knowledgeBase) {
            contextBuilder.append("Question: ")
                    .append(safe(item.getQuestion()))
                    .append("\nAnswer: ")
                    .append(safe(item.getAnswer()))
                    .append("\nKeywords: ")
                    .append(safe(item.getKeywords()))
                    .append("\n\n");
        }

        return """
                You are an internal application support chatbot.
                Answer clearly and briefly using the known application knowledge below first.
                If the exact answer is not in the knowledge, still try to provide a helpful professional answer.
                If you are unsure, say that the user should contact the application support team.
                Do not mention AI, model names, or Ollama.

                Known application knowledge:
                %s

                User question:
                %s
                """.formatted(contextBuilder, safe(question));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
