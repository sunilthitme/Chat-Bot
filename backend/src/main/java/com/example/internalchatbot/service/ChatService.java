package com.example.internalchatbot.service;

import com.example.internalchatbot.entity.ChatQuestion;
import com.example.internalchatbot.repository.ChatQuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

// Service contains the chatbot matching logic.
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String NOT_FOUND_REPLY = "Sorry, I could not find information.";
    private static final int MAX_MATCHES = 1;

    private final ChatQuestionRepository chatQuestionRepository;
    private final OllamaAiService ollamaAiService;

    public ChatService(ChatQuestionRepository chatQuestionRepository, OllamaAiService ollamaAiService) {
        this.chatQuestionRepository = chatQuestionRepository;
        this.ollamaAiService = ollamaAiService;
    }

    public String ask(String message) {
        long startTime = System.currentTimeMillis();
        log.info("Chat request received. messageLength={}", message == null ? 0 : message.length());

        String normalizedMessage = normalize(message);
        log.info("Chat message normalized. normalizedMessage={}", normalizedMessage);

        List<ChatQuestion> directMatches = chatQuestionRepository.searchByQuestionOrKeywords(
                normalizedMessage,
                PageRequest.of(0, MAX_MATCHES)
        );
        if (!directMatches.isEmpty()) {
            log.info("Direct chat match found. questionId={}", directMatches.getFirst().getId());
            logIfSlow(startTime);
            return directMatches.getFirst().getAnswer();
        }

        String reply = findByImportantWords(message, normalizedMessage);
        logIfSlow(startTime);
        return reply;
    }

    private String findByImportantWords(String originalMessage, String normalizedMessage) {
        List<String> words = Arrays.stream(normalizedMessage.split(" "))
                .filter(word -> word.length() > 2)
                .toList();

        for (String word : words) {
            log.info("Searching chat answer by keyword. keyword={}", word);
            List<ChatQuestion> matches = chatQuestionRepository.searchByQuestionOrKeywords(
                    word,
                    PageRequest.of(0, MAX_MATCHES)
            );
            if (!matches.isEmpty()) {
                log.info("Keyword chat match found. keyword={}, questionId={}", word, matches.getFirst().getId());
                return matches.getFirst().getAnswer();
            }
        }

        log.warn("No chat answer found for message. normalizedMessage={}", normalizedMessage);
        String aiReply = ollamaAiService.generateReply(originalMessage);
        if (!aiReply.isBlank()) {
            log.info("Ollama AI fallback answer returned");
            return aiReply;
        }

        return NOT_FOUND_REPLY;
    }

    private void logIfSlow(long startTime) {
        long durationMs = System.currentTimeMillis() - startTime;
        if (durationMs > 10_000) {
            log.error("Chat response took more than 10 seconds. durationMs={}", durationMs);
        } else {
            log.info("Chat response completed. durationMs={}", durationMs);
        }
    }

    private String normalize(String text) {
        return text == null
                ? ""
                : text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
