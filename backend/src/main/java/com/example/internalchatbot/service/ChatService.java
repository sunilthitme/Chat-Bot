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
    private final int ragTopK;
    private final double minScore;
    private final int maxContextChars;

    public ChatService(
            ChatQuestionRepository chatQuestionRepository,
            LlmService llmService,
            SessionService sessionService,
            EmbeddingService embeddingService,
            VectorStoreService vectorStoreService,
            @Value("${rag.top-k:6}") int ragTopK,
            @Value("${rag.min-score:0.20}") double minScore,
            @Value("${rag.max-context-chars:9000}") int maxContextChars
    ) {
        this.chatQuestionRepository = chatQuestionRepository;
        this.llmService = llmService;
        this.sessionService = sessionService;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.ragTopK = ragTopK;
        this.minScore = minScore;
        this.maxContextChars = maxContextChars;
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

        String memory = privateMode ? "" : sessionService.memoryForSession(session.getId(), 5_000);
        String rewrittenQuestion = rewriteQuestion(message, memory);
        String storedAnswer = findStoredAnswer(normalize(rewrittenQuestion));
        List<VectorSearchResult> retrievedKnowledge = retrieveKnowledge(rewrittenQuestion, privateMode);
        String prompt = buildPrompt(message, rewrittenQuestion, memory, storedAnswer, retrievedKnowledge, privateMode);

        String reply = generateReply(prompt, storedAnswer);
        sessionService.saveMessage(session.getId(), "assistant", reply, privateMode);

        return new ChatResponse(
                session.getId(),
                reply,
                privateMode,
                toSourceReferences(retrievedKnowledge)
        );
    }

    public ChatResponse stream(ChatRequest request, Consumer<String> onToken) {
        String message = request.getMessage();
        ChatSession session = sessionService.getOrCreateSession(
                request.getSessionId(),
                request.getUserKey(),
                request.isPrivateMode(),
                message
        );
        boolean privateMode = session.isPrivateMode() || request.isPrivateMode();

        sessionService.saveMessage(session.getId(), "user", message, privateMode);

        String memory = privateMode ? "" : sessionService.memoryForSession(session.getId(), 5_000);
        String rewrittenQuestion = rewriteQuestion(message, memory);
        String storedAnswer = findStoredAnswer(normalize(rewrittenQuestion));
        List<VectorSearchResult> retrievedKnowledge = retrieveKnowledge(rewrittenQuestion, privateMode);
        String prompt = buildPrompt(message, rewrittenQuestion, memory, storedAnswer, retrievedKnowledge, privateMode);

        if (!llmService.isEnabled()) {
            String fallback = storedAnswer == null ? NOT_FOUND_REPLY : storedAnswer;
            onToken.accept(fallback);
            sessionService.saveMessage(session.getId(), "assistant", fallback, privateMode);
            return new ChatResponse(session.getId(), fallback, privateMode, toSourceReferences(retrievedKnowledge));
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
        return new ChatResponse(session.getId(), reply, privateMode, toSourceReferences(retrievedKnowledge));
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
            return vectorStoreService.search(message, queryVector, ragTopK * 2)
                    .stream()
                    .filter(result -> result.score() >= minScore)
                    .limit(ragTopK)
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("RAG retrieval skipped because embedding generation failed.", ex);
            return List.of();
        }
    }

    private String rewriteQuestion(String message, String memory) {
        if (!llmService.isEnabled() || memory == null || memory.isBlank()) {
            return message;
        }

        String prompt = """
                Rewrite the user's latest question into a standalone search query.
                Keep entity names, document names, numbers, and technical terms.
                Do not answer the question.

                Conversation:
                %s

                Latest question:
                %s

                Standalone search query:
                """.formatted(memory, message);
        try {
            String rewritten = llmService.generateResponse(prompt, 0.0).trim();
            return rewritten.isBlank() ? message : rewritten;
        } catch (RuntimeException ex) {
            log.debug("Query rewrite failed. Using original user message.");
            return message;
        }
    }

    private String buildPrompt(
            String message,
            String rewrittenQuestion,
            String memory,
            String storedAnswer,
            List<VectorSearchResult> retrievedKnowledge,
            boolean privateMode
    ) {
        String knowledge = formatKnowledge(retrievedKnowledge);

        return """
                You are a production-grade enterprise AI assistant with a conversational style similar to ChatGPT.
                Follow these rules:
                1. Use current session memory to understand follow-up questions.
                2. Prefer the internal DB answer when present.
                3. Use retrieved document and website knowledge only when relevant.
                4. Answer naturally; do not copy chunks verbatim unless quoting a short exact phrase is useful.
                5. If the context is insufficient, say what is missing and give the safest next step.
                6. When using retrieved sources, cite them inline using the source name and page when available.
                7. Never leak memory across sessions.
                8. If private mode is enabled, do not mention storing or learning from this conversation.

                Private mode: %s

                Current session memory:
                %s

                Rewritten standalone question for retrieval:
                %s

                Internal DB answer:
                %s

                Retrieved ranked context:
                %s

                User's latest message:
                %s
                """.formatted(
                privateMode ? "enabled" : "disabled",
                memory.isBlank() ? "No previous messages in this session." : memory,
                rewrittenQuestion,
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
        } catch (RuntimeException ex) {
            log.warn("Ollama request failed. Falling back to stored answer when available.", ex);
        }

        return storedAnswer == null ? NOT_FOUND_REPLY : storedAnswer;
    }

    private List<SourceReference> toSourceReferences(List<VectorSearchResult> results) {
        return results.stream()
                .map(result -> new SourceReference(
                        result.sourceName(),
                        result.sourceType(),
                        result.sourceUrl(),
                        result.pageNumber(),
                        result.sectionTitle(),
                        result.score(),
                        result.content().length() > 180 ? result.content().substring(0, 180) + "..." : result.content()
                ))
                .toList();
    }

    private String formatKnowledge(List<VectorSearchResult> retrievedKnowledge) {
        StringBuilder builder = new StringBuilder();
        int sourceNumber = 1;
        for (VectorSearchResult result : retrievedKnowledge) {
            if (builder.length() >= maxContextChars) {
                break;
            }
            builder.append("Source ").append(sourceNumber++).append('\n');
            builder.append("Name: ").append(result.sourceName()).append('\n');
            builder.append("Type: ").append(result.sourceType()).append('\n');
            if (result.sourceUrl() != null && !result.sourceUrl().isBlank()) {
                builder.append("URL: ").append(result.sourceUrl()).append('\n');
            }
            if (result.pageNumber() != null) {
                builder.append("Page: ").append(result.pageNumber()).append('\n');
            }
            if (result.sectionTitle() != null && !result.sectionTitle().isBlank()) {
                builder.append("Section: ").append(result.sectionTitle()).append('\n');
            }
            builder.append("Relevance: ").append(String.format("%.3f", result.score())).append('\n');
            builder.append("Content: ").append(trim(result.content(), 1_600)).append("\n\n");
        }
        return trim(builder.toString(), maxContextChars);
    }

    private String trim(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private String normalize(String text) {
        return text == null
                ? ""
                : text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
