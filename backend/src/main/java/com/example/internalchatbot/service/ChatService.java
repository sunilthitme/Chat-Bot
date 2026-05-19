package com.example.internalchatbot.service;

import com.example.internalchatbot.dto.ChatRequest;
import com.example.internalchatbot.dto.ChatResponse;
import com.example.internalchatbot.entity.ChatQuestion;
import com.example.internalchatbot.entity.ChatSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String NOT_FOUND_REPLY = "Sorry, I could not find information.";

    private final ChatQuestionIndexService chatQuestionIndexService;
    private final LlmService llmService;
    private final SessionService sessionService;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final int ragTopK;
    private final double minScore;
    private final int maxContextChars;
    private final int internalSearchLimit;
    private final Map<String, Optional<String>> answerCache;

    public ChatService(
            ChatQuestionIndexService chatQuestionIndexService,
            LlmService llmService,
            SessionService sessionService,
            EmbeddingService embeddingService,
            VectorStoreService vectorStoreService,
            @Value("${rag.top-k:5}") int ragTopK,
            @Value("${rag.min-score:0.20}") double minScore,
            @Value("${rag.max-context-chars:9000}") int maxContextChars,
            @Value("${chat.internal-search-limit:5}") int internalSearchLimit,
            @Value("${chat.answer-cache-size:256}") int answerCacheSize
    ) {
        this.chatQuestionIndexService = chatQuestionIndexService;
        this.llmService = llmService;
        this.sessionService = sessionService;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.ragTopK = Math.max(1, Math.min(ragTopK, 10));
        this.minScore = minScore;
        this.maxContextChars = maxContextChars;
        this.internalSearchLimit = Math.max(1, Math.min(internalSearchLimit, 10));
        int safeCacheSize = Math.max(32, answerCacheSize);
        this.answerCache = Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Optional<String>> eldest) {
                return size() > safeCacheSize;
            }
        });
    }

    public String ask(String message) {
        ChatRequest request = new ChatRequest();
        request.setMessage(message);
        return ask(request).getReply();
    }

    public ChatResponse ask(ChatRequest request) {
        PreparedChat prepared = prepare(request);
        long llmStartedAt = System.nanoTime();
        String reply = generateReply(prepared.prompt(), prepared.storedAnswer(), prepared.requestId());
        sessionService.saveMessage(prepared.sessionId(), "assistant", reply, prepared.privateMode());

        log.info(
                "chat request completed requestId={} sessionId={} stream=false sources={} llmMs={} totalMs={}",
                prepared.requestId(),
                prepared.sessionId(),
                prepared.retrievedKnowledge().size(),
                elapsedMillis(llmStartedAt),
                elapsedMillis(prepared.startedAt())
        );

        return new ChatResponse(
                prepared.sessionId(),
                reply,
                prepared.privateMode()
        );
    }

    public ChatResponse stream(ChatRequest request, Consumer<String> onToken) {
        PreparedChat prepared = prepare(request);
        long llmStartedAt = System.nanoTime();

        if (!llmService.isEnabled()) {
            String fallback = prepared.storedAnswer() == null ? NOT_FOUND_REPLY : prepared.storedAnswer();
            onToken.accept(fallback);
            sessionService.saveMessage(prepared.sessionId(), "assistant", fallback, prepared.privateMode());
            return response(prepared, fallback, llmStartedAt, true);
        }

        StringBuilder streamedReply = new StringBuilder();
        try {
            llmService.streamResponse(prepared.prompt(), token -> {
                streamedReply.append(token);
                onToken.accept(token);
            });
        } catch (RestClientException ex) {
            log.warn("Ollama streaming failed requestId={}. Falling back to stored answer when available.", prepared.requestId(), ex);
        }

        String reply = streamedReply.isEmpty()
                ? (prepared.storedAnswer() == null ? NOT_FOUND_REPLY : prepared.storedAnswer())
                : streamedReply.toString();
        if (streamedReply.isEmpty()) {
            onToken.accept(reply);
        }
        sessionService.saveMessage(prepared.sessionId(), "assistant", reply, prepared.privateMode());
        return response(prepared, reply, llmStartedAt, true);
    }

    private PreparedChat prepare(ChatRequest request) {
        long startedAt = System.nanoTime();
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        String message = request.getMessage() == null ? "" : request.getMessage().trim();

        long sessionStartedAt = System.nanoTime();
        ChatSession session = sessionService.getOrCreateSession(
                request.getSessionId(),
                request.getUserKey(),
                request.isPrivateMode(),
                message
        );
        boolean privateMode = session.isPrivateMode() || request.isPrivateMode();
        sessionService.saveMessage(session.getId(), "user", message, privateMode);
        long sessionMs = elapsedMillis(sessionStartedAt);

        long memoryStartedAt = System.nanoTime();
        String memory = privateMode ? "" : sessionService.memoryForSession(session.getId(), 5_000);
        String retrievalQuestion = buildRetrievalQuery(message, memory);
        long memoryMs = elapsedMillis(memoryStartedAt);

        long dbStartedAt = System.nanoTime();
        String normalizedMessage = normalize(message);
        String storedAnswer = findStoredAnswer(normalizedMessage, requestId);
        long dbMs = elapsedMillis(dbStartedAt);

        long ragStartedAt = System.nanoTime();
        List<VectorSearchResult> retrievedKnowledge = retrieveKnowledge(retrievalQuestion, privateMode);
        long ragMs = elapsedMillis(ragStartedAt);

        boolean explicitSourceRequest = wantsSourceDetails(message);
        String prompt = buildPrompt(message, retrievalQuestion, memory, storedAnswer, retrievedKnowledge, privateMode, explicitSourceRequest);
        log.info(
                "chat request prepared requestId={} sessionId={} privateMode={} dbHit={} sources={} sessionMs={} memoryMs={} dbMs={} ragMs={} prepMs={}",
                requestId,
                session.getId(),
                privateMode,
                storedAnswer != null,
                retrievedKnowledge.size(),
                sessionMs,
                memoryMs,
                dbMs,
                ragMs,
                elapsedMillis(startedAt)
        );

        return new PreparedChat(
                requestId,
                startedAt,
                session.getId(),
                privateMode,
                storedAnswer,
                retrievedKnowledge,
                prompt,
                explicitSourceRequest
        );
    }

    private String findStoredAnswer(String normalizedMessage, String requestId) {
        if (normalizedMessage == null || normalizedMessage.isBlank()) {
            return null;
        }

        Optional<String> cached = answerCache.get(normalizedMessage);
        if (cached != null) {
            log.debug("internal DB answer cache hit requestId={} queryHash={}", requestId, queryHash(normalizedMessage));
            return cached.orElse(null);
        }

        List<ChatQuestion> candidates = chatQuestionIndexService.search(normalizedMessage, internalSearchLimit);
        String answer = rankStoredAnswer(normalizedMessage, candidates);
        answerCache.put(normalizedMessage, Optional.ofNullable(answer));

        log.debug(
                "internal DB lookup completed requestId={} queryHash={} candidates={} hit={}",
                requestId,
                queryHash(normalizedMessage),
                candidates.size(),
                answer != null
        );
        return answer;
    }

    private String rankStoredAnswer(String normalizedMessage, List<ChatQuestion> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }

        List<String> queryTokens = chatQuestionIndexService.tokenize(normalizedMessage);
        return candidates.stream()
                .max((left, right) -> Double.compare(
                        storedAnswerScore(left, normalizedMessage, queryTokens),
                        storedAnswerScore(right, normalizedMessage, queryTokens)
                ))
                .map(ChatQuestion::getAnswer)
                .orElse(null);
    }

    private double storedAnswerScore(ChatQuestion candidate, String normalizedMessage, List<String> queryTokens) {
        String question = defaultString(candidate.getNormalizedQuestion());
        String keywords = defaultString(candidate.getNormalizedKeywords());
        String searchable = question + " " + keywords;

        if (question.equals(normalizedMessage) || keywords.equals(normalizedMessage)) {
            return 100;
        }
        if (question.startsWith(normalizedMessage) || keywords.startsWith(normalizedMessage)) {
            return 75;
        }

        long matches = queryTokens.stream().filter(searchable::contains).count();
        return matches * 10.0 / Math.max(1, queryTokens.size());
    }

    private List<VectorSearchResult> retrieveKnowledge(String message, boolean privateMode) {
        if (privateMode) {
            return List.of();
        }

        try {
            List<Double> queryVector = embeddingService.embed(message);
            return vectorStoreService.search(message, queryVector, ragTopK)
                    .stream()
                    .filter(result -> result.score() >= minScore)
                    .limit(ragTopK)
                    .toList();
        } catch (RuntimeException ex) {
            log.warn("RAG retrieval skipped because embedding generation failed.", ex);
            return List.of();
        }
    }

    private String buildRetrievalQuery(String message, String memory) {
        if (memory == null || memory.isBlank()) {
            return message;
        }
        return trim(message + "\nRecent session context:\n" + memory, 1_200);
    }

    private String buildPrompt(
            String message,
            String retrievalQuestion,
            String memory,
            String storedAnswer,
            List<VectorSearchResult> retrievedKnowledge,
            boolean privateMode,
            boolean explicitSourceRequest
    ) {
        String knowledge = formatKnowledge(retrievedKnowledge, explicitSourceRequest);

        return """
                You are a production-grade enterprise AI assistant with a conversational style similar to ChatGPT.
                Follow these rules:
                1. Use current session memory to understand follow-up questions.
                2. Prefer the internal DB answer when present.
                3. Use retrieved document and website knowledge only when relevant.
                4. Answer naturally; do not copy chunks verbatim unless quoting a short exact phrase is useful.
                5. If the context is insufficient, say what is missing and give the safest next step.
                6. Use retrieved context silently unless the user explicitly asks for sources, citations, links, or references.
                7. Never leak memory across sessions.
                8. If private mode is enabled, do not mention storing or learning from this conversation.
                9. Never expose session IDs, vector IDs, embedding IDs, database IDs, or internal metadata.
                10. If sources were not requested, do not include URLs or source lists in the answer.

                Private mode: %s

                Current session memory:
                %s

                Retrieval query:
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
                retrievalQuestion,
                storedAnswer == null ? "No matching DB answer." : storedAnswer,
                knowledge.isBlank() ? "No relevant vector knowledge found." : knowledge,
                message
        );
    }

    private String generateReply(String prompt, String storedAnswer, String requestId) {
        if (!llmService.isEnabled()) {
            return storedAnswer == null ? NOT_FOUND_REPLY : storedAnswer;
        }

        try {
            String llmReply = llmService.generateResponse(prompt);
            if (!llmReply.isBlank()) {
                return llmReply;
            }
        } catch (RuntimeException ex) {
            log.warn("Ollama request failed requestId={}. Falling back to stored answer when available.", requestId, ex);
        }

        return storedAnswer == null ? NOT_FOUND_REPLY : storedAnswer;
    }

    private ChatResponse response(PreparedChat prepared, String reply, long llmStartedAt, boolean stream) {
        log.info(
                "chat request completed requestId={} sessionId={} stream={} sources={} llmMs={} totalMs={}",
                prepared.requestId(),
                prepared.sessionId(),
                stream,
                prepared.retrievedKnowledge().size(),
                elapsedMillis(llmStartedAt),
                elapsedMillis(prepared.startedAt())
        );
        return new ChatResponse(
                prepared.sessionId(),
                reply,
                prepared.privateMode()
        );
    }

    private String publicSourceName(VectorSearchResult result) {
        if ("url".equalsIgnoreCase(result.sourceType()) && result.sourceUrl() != null && !result.sourceUrl().isBlank()) {
            return result.sourceUrl();
        }
        String value = publicText(result.sourceName(), "Knowledge source");
        if (looksInternal(value)) {
            return "Knowledge source";
        }
        return value;
    }

    private String publicSourceUrl(String sourceUrl) {
        String value = publicText(sourceUrl, null);
        if (value == null || looksInternal(value)) {
            return null;
        }
        return value;
    }

    private String publicText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value
                .replaceAll("(?i)embedding-[0-9a-f-]+", "")
                .replaceAll("(?i)session[_ -]?[0-9a-f-]{8,}", "")
                .replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean looksInternal(String value) {
        return value == null
                || value.isBlank()
                || value.matches(".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*")
                || value.matches("(?i).*\\b(embedding|vector|session)[_-]?[0-9a-f-]{6,}.*");
    }

    private boolean wantsSourceDetails(String message) {
        String normalized = normalize(message);
        return normalized.contains("source")
                || normalized.contains("citation")
                || normalized.contains("cite")
                || normalized.contains("reference")
                || normalized.contains("link")
                || normalized.contains("url");
    }

    private String formatKnowledge(List<VectorSearchResult> retrievedKnowledge, boolean includeSourceDetails) {
        StringBuilder builder = new StringBuilder();
        int sourceNumber = 1;
        for (VectorSearchResult result : retrievedKnowledge) {
            if (builder.length() >= maxContextChars) {
                break;
            }
            builder.append("Source ").append(sourceNumber++).append('\n');
            builder.append("Name: ").append(includeSourceDetails ? publicSourceName(result) : "Retrieved knowledge").append('\n');
            builder.append("Type: ").append(result.sourceType()).append('\n');
            if (includeSourceDetails) {
                String publicUrl = publicSourceUrl(result.sourceUrl());
                if (publicUrl != null && !publicUrl.isBlank()) {
                    builder.append("URL: ").append(publicUrl).append('\n');
                }
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

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String queryHash(String normalizedMessage) {
        return Integer.toHexString(normalizedMessage.hashCode());
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private record PreparedChat(
            String requestId,
            long startedAt,
            String sessionId,
            boolean privateMode,
            String storedAnswer,
            List<VectorSearchResult> retrievedKnowledge,
            String prompt,
            boolean explicitSourceRequest
    ) {
    }
}
