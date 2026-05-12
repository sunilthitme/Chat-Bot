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
        String normalizedMessage = normalize(message);
        String storedAnswer = findStoredAnswer(normalizedMessage);

        if (storedAnswer != null) {
            return improveStoredAnswerWithLlm(message, storedAnswer);
        }

        return answerWithLlm(message);
    }

    private String findStoredAnswer(String normalizedMessage) {
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

        return null;
    }

    private String improveStoredAnswerWithLlm(String message, String storedAnswer) {
        if (!llmService.isEnabled()) {
            return storedAnswer;
        }

        try {
            String llmReply = llmService.generateResponseWithContext(message, storedAnswer);
            return llmReply.isBlank() ? storedAnswer : llmReply;
        } catch (RestClientException ex) {
            log.warn("Ollama request failed. Returning stored chat answer.", ex);
            return storedAnswer;
        }
    }

    private String answerWithLlm(String message) {
        if (!llmService.isEnabled()) {
            return NOT_FOUND_REPLY;
        }

        try {
            String llmReply = llmService.generateResponse(message);
            return llmReply.isBlank() ? NOT_FOUND_REPLY : llmReply;
        } catch (RestClientException ex) {
            log.warn("Ollama request failed and no stored chat answer was found.", ex);
            return NOT_FOUND_REPLY;
        }
    }

    private String normalize(String text) {
        return text == null
                ? ""
                : text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
