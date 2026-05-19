package com.example.internalchatbot.ai.memory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ConversationMemoryService {

    private final SessionService sessionService;
    private final int maxMemoryChars;
    private final int maxRetrievalQueryChars;

    public ConversationMemoryService(
            SessionService sessionService,
            @Value("${chat.memory.max-chars:5000}") int maxMemoryChars,
            @Value("${chat.memory.retrieval-query-chars:1200}") int maxRetrievalQueryChars
    ) {
        this.sessionService = sessionService;
        this.maxMemoryChars = Math.max(500, maxMemoryChars);
        this.maxRetrievalQueryChars = Math.max(200, maxRetrievalQueryChars);
    }

    public String loadMemory(String sessionId, boolean privateMode) {
        if (privateMode || sessionId == null || sessionId.isBlank()) {
            return "";
        }
        return sessionService.memoryForSession(sessionId, maxMemoryChars);
    }

    public String buildRetrievalQuery(String message, String memory) {
        if (memory == null || memory.isBlank()) {
            return message == null ? "" : message;
        }
        return trim((message == null ? "" : message) + "\nRecent session context:\n" + memory, maxRetrievalQueryChars);
    }

    private String trim(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
