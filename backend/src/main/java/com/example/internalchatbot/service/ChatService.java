package com.example.internalchatbot.service;

import com.example.internalchatbot.dto.ChatRequest;
import com.example.internalchatbot.dto.ChatResponse;
import com.example.internalchatbot.dto.SourceReference;
import com.example.internalchatbot.entity.ChatQuestion;
import com.example.internalchatbot.entity.ChatSession;
import com.example.internalchatbot.repository.ChatQuestionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

// Service contains the chatbot matching logic.
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String NOT_FOUND_REPLY = "Sorry, I could not find information.";

    private final ChatQuestionRepository chatQuestionRepository;
    private final LlmService llmService;
    private final SessionService sessionService;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final int ragTopK;

    public ChatService(
            ChatQuestionRepository chatQuestionRepository,
            LlmService llmService,
            SessionService sessionService,
            EmbeddingService embeddingService,
            VectorStoreService vectorStoreService,
            KnowledgeIngestionService knowledgeIngestionService,
            @Value("${rag.top-k:5}") int ragTopK
    ) {
        this.chatQuestionRepository = chatQuestionRepository;
        this.llmService = llmService;
        this.sessionService = sessionService;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.ragTopK = ragTopK;
    }

    public String ask(String message) {
        ChatRequest request = new ChatRequest();
        request.setMessage(message);
        return ask(request).getReply();
    }

    public ChatResponse ask(ChatRequest request) {
        String message = request.getMessage();
        ChatSession session = sessionService.getOrCreateSession(
                request.getSessionId(),
                request.getUserKey(),
                request.isPrivateMode(),
                message
        );
        boolean privateMode = session.isPrivateMode() || request.isPrivateMode();

        sessionService.saveMessage(session.getId(), "user", message, privateMode);

        String storedAnswer = findStoredAnswer(normalize(message));
        List<VectorSearchResult> retrievedKnowledge = retrieveKnowledge(message, privateMode);
        String prompt = buildPrompt(message, session.getId(), storedAnswer, retrievedKnowledge, privateMode);

        String reply = generateReply(prompt, storedAnswer);
        sessionService.saveMessage(session.getId(), "assistant", reply, privateMode);
        knowledgeIngestionService.storeUsefulKnowledge(session.getId(), "chat-session-" + session.getId(), reply, privateMode);

        return new ChatResponse(
                session.getId(),
                reply,
                privateMode,
                toSourceReferences(retrievedKnowledge)
        );
    }

    public void stream(ChatRequest request, Consumer<String> onToken) {
        String message = request.getMessage();
        ChatSession session = sessionService.getOrCreateSession(
                request.getSessionId(),
                request.getUserKey(),
                request.isPrivateMode(),
                message
        );
        boolean privateMode = session.isPrivateMode() || request.isPrivateMode();

        sessionService.saveMessage(session.getId(), "user", message, privateMode);

        String storedAnswer = findStoredAnswer(normalize(message));
        List<VectorSearchResult> retrievedKnowledge = retrieveKnowledge(message, privateMode);
        String prompt = buildPrompt(message, session.getId(), storedAnswer, retrievedKnowledge, privateMode);

        if (!llmService.isEnabled()) {
            String fallback = storedAnswer == null ? NOT_FOUND_REPLY : storedAnswer;
            onToken.accept(fallback);
            sessionService.saveMessage(session.getId(), "assistant", fallback, privateMode);
            return;
        }

        StringBuilder streamedReply = new StringBuilder();
        try {
            llmService.streamResponse(prompt, token -> {
                streamedReply.append(token);
                onToken.accept(token);
            });
        } catch (RestClientException ex) {
            log.warn("Ollama streaming failed. Falling back to stored answer when available.", ex);
        }

        String reply = streamedReply.isEmpty()
                ? (storedAnswer == null ? NOT_FOUND_REPLY : storedAnswer)
                : streamedReply.toString();
        if (streamedReply.isEmpty()) {
            onToken.accept(reply);
        }
        sessionService.saveMessage(session.getId(), "assistant", reply, privateMode);
        knowledgeIngestionService.storeUsefulKnowledge(session.getId(), "chat-session-" + session.getId(), reply, privateMode);
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

    private List<VectorSearchResult> retrieveKnowledge(String message, boolean privateMode) {
        if (privateMode) {
            return List.of();
        }

        try {
            List<Double> queryVector = embeddingService.embed(message);
            return vectorStoreService.search(queryVector, ragTopK);
        } catch (RuntimeException ex) {
            log.warn("RAG retrieval skipped because embedding generation failed.", ex);
            return List.of();
        }
    }

    private String buildPrompt(
            String message,
            String sessionId,
            String storedAnswer,
            List<VectorSearchResult> retrievedKnowledge,
            boolean privateMode
    ) {
        String memory = privateMode ? "" : sessionService.memoryForSession(sessionId);
        String knowledge = retrievedKnowledge.stream()
                .map(result -> "- [" + result.sourceName() + "] " + result.content())
                .reduce("", (left, right) -> left + right + "\n");

        return """
                You are an enterprise internal AI assistant.
                Follow these rules:
                1. Prefer the internal DB answer when present.
                2. Use retrieved knowledge only when relevant.
                3. Use current session memory only; never assume memory from another chat.
                4. If private mode is enabled, do not mention storing or learning from the conversation.
                5. Be concise, accurate, and operationally useful.

                Private mode: %s

                Current session memory:
                %s

                Internal DB answer:
                %s

                Retrieved enterprise knowledge:
                %s

                User question:
                %s
                """.formatted(
                privateMode ? "enabled" : "disabled",
                memory.isBlank() ? "No previous messages in this session." : memory,
                storedAnswer == null ? "No matching DB answer." : storedAnswer,
                knowledge.isBlank() ? "No relevant vector knowledge found." : knowledge,
                message
        );
    }

    private String generateReply(String prompt, String storedAnswer) {
        if (!llmService.isEnabled()) {
            return storedAnswer == null ? NOT_FOUND_REPLY : storedAnswer;
        }

        try {
            String llmReply = llmService.generateResponse(prompt);
            if (!llmReply.isBlank()) {
                return llmReply;
            }
        } catch (RestClientException ex) {
            log.warn("Ollama request failed. Falling back to stored answer when available.", ex);
        }

        return storedAnswer == null ? NOT_FOUND_REPLY : storedAnswer;
    }

    private List<SourceReference> toSourceReferences(List<VectorSearchResult> results) {
        return results.stream()
                .map(result -> new SourceReference(
                        result.sourceName(),
                        result.sourceType(),
                        result.score(),
                        result.content().length() > 180 ? result.content().substring(0, 180) + "..." : result.content()
                ))
                .toList();
    }

    private String normalize(String text) {
        return text == null
                ? ""
                : text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
