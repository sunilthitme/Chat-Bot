package com.example.internalchatbot.service;

import com.example.internalchatbot.entity.ChatQuestion;
import com.example.internalchatbot.repository.ChatQuestionRepository;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

// Service contains the chatbot matching logic.
@Service
public class ChatService {

    private static final String NOT_FOUND_REPLY = "Sorry, I could not find information.";

    private final ChatQuestionRepository chatQuestionRepository;

    public ChatService(ChatQuestionRepository chatQuestionRepository) {
        this.chatQuestionRepository = chatQuestionRepository;
    }

    public String ask(String message) {
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

    private String normalize(String text) {
        return text == null
                ? ""
                : text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
