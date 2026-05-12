package com.example.internalchatbot.service;

import com.example.internalchatbot.entity.ChatQuestion;
import com.example.internalchatbot.repository.ChatQuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.List;

// Service contains the chatbot matching logic.
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String NOT_FOUND_REPLY = "Sorry, I could not find information.";

    private final ChatQuestionRepository chatQuestionRepository;
    private final LlmService llmService;

    public ChatService(ChatQuestionRepository chatQuestionRepository, LlmService llmService) {
        this.chatQuestionRepository = chatQuestionRepository;
        this.llmService = llmService;
    }

    public String ask(String message) {
        if (llmService.isEnabled()) {
            String llmReply = askLlm(message);
            if (!llmReply.isBlank()) {
                return llmReply;
            }
        }

        String normalizedMessage = normalize(message);

        List<ChatQuestion> directMatches = chatQuestionRepository.searchByQuestionOrKeywords(normalizedMessage);
        if (!directMatches.isEmpty()) {
            return directMatches.getFirst().getAnswer();
        }

        return findByImportantWords(normalizedMessage);
    }

    private String findByImportantWords(String normalizedMessage) {
        List<String> words = Arrays.stream(normalizedMessage.split(" "))
                .filter(word -> word.length() > 2)
                .toList();

        for (String word : words) {
            List<ChatQuestion> matches = chatQuestionRepository.searchByQuestionOrKeywords(word);
            if (!matches.isEmpty()) {
                return matches.getFirst().getAnswer();
            }
        }

        return NOT_FOUND_REPLY;
    }

    private String askLlm(String message) {
        try {
            return llmService.generateResponse(message);
        } catch (RestClientException ex) {
            log.warn("Ollama request failed. Falling back to stored chat answers.", ex);
            return "";
        }
    }

    private String normalize(String text) {
        return text == null
                ? ""
                : text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
