package com.example.internalchatbot.ai.rag;

import com.example.internalchatbot.ai.ingestion.KnowledgeIngestionService;
import com.example.internalchatbot.ai.llm.LlmService;
import com.example.internalchatbot.ai.memory.ConversationMemoryService;
import com.example.internalchatbot.ai.memory.SessionService;
import com.example.internalchatbot.ai.prompts.PromptBuilder;
import com.example.internalchatbot.ai.retrieval.ChatQuestionIndexService;
import com.example.internalchatbot.ai.retrieval.RagRetrievalService;
import com.example.internalchatbot.ai.vectorstore.VectorSearchResult;
import com.example.internalchatbot.dto.ChatRequest;
import com.example.internalchatbot.dto.ChatResponse;
import com.example.internalchatbot.dto.SourceReferenceResponse;
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
public class AiOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(AiOrchestratorService.class);
    private static final String NOT_FOUND_REPLY = "Information not found in the indexed knowledge.";
    private static final String INDEXING_REPLY = "Document is still being indexed. Please wait.";

    private final ChatQuestionIndexService chatQuestionIndexService;
    private final LlmService llmService;
    private final SessionService sessionService;
    private final ConversationMemoryService conversationMemoryService;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final RagRetrievalService ragRetrievalService;
    private final PromptBuilder promptBuilder;
    private final int internalSearchLimit;
    private final Map<String, Optional<String>> answerCache;

    public AiOrchestratorService(
            ChatQuestionIndexService chatQuestionIndexService,
            LlmService llmService,
            SessionService sessionService,
            ConversationMemoryService conversationMemoryService,
            KnowledgeIngestionService knowledgeIngestionService,
            RagRetrievalService ragRetrievalService,
            PromptBuilder promptBuilder,
            @Value("${chat.internal-search-limit:5}") int internalSearchLimit,
            @Value("${chat.answer-cache-size:256}") int answerCacheSize
    ) {
        this.chatQuestionIndexService = chatQuestionIndexService;
        this.llmService = llmService;
        this.sessionService = sessionService;
        this.conversationMemoryService = conversationMemoryService;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.ragRetrievalService = ragRetrievalService;
        this.promptBuilder = promptBuilder;
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
        if (prepared.indexingActive()) {
            return indexingResponse(prepared, llmStartedAt, false, null);
        }
        if (!prepared.hasGrounding()) {
            return noGroundingResponse(prepared, llmStartedAt, false, null);
        }

        String reply = generateReply(prepared.prompt(), prepared.storedAnswer(), prepared.requestId());
        sessionService.saveMessage(prepared.sessionId(), "assistant", reply, prepared.privateMode());

        log.info(
                "chat request completed requestId={} stream=false retrievedChunks={} llmMs={} totalMs={}",
                prepared.requestId(),
                prepared.retrievedKnowledge().size(),
                elapsedMillis(llmStartedAt),
                elapsedMillis(prepared.startedAt())
        );

        return new ChatResponse(
                reply,
                prepared.privateMode(),
                sourceReferences(prepared.retrievedKnowledge())
        );
    }

    public ChatResponse stream(ChatRequest request, Consumer<String> onToken) {
        PreparedChat prepared = prepare(request);
        long llmStartedAt = System.nanoTime();
        if (prepared.indexingActive()) {
            return indexingResponse(prepared, llmStartedAt, true, onToken);
        }
        if (!prepared.hasGrounding()) {
            return noGroundingResponse(prepared, llmStartedAt, true, onToken);
        }

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
        if (!privateMode && knowledgeIngestionService.hasActiveIngestion(session.getId())) {
            log.info(
                    "chat request blocked while ingestion is active requestId={} sessionMs={} totalMs={}",
                    requestId,
                    elapsedMillis(sessionStartedAt),
                    elapsedMillis(startedAt)
            );
            return new PreparedChat(
                    requestId,
                    startedAt,
                    session.getId(),
                    false,
                    null,
                    List.of(),
                    "",
                    false,
                    true
            );
        }
        sessionService.saveMessage(session.getId(), "user", message, privateMode);
        long sessionMs = elapsedMillis(sessionStartedAt);

        long memoryStartedAt = System.nanoTime();
        String memory = conversationMemoryService.loadMemory(session.getId(), privateMode);
        String retrievalQuestion = conversationMemoryService.buildRetrievalQuery(message, memory);
        long memoryMs = elapsedMillis(memoryStartedAt);

        long dbStartedAt = System.nanoTime();
        String normalizedMessage = normalize(message);
        String storedAnswer = findStoredAnswer(normalizedMessage, requestId);
        long dbMs = elapsedMillis(dbStartedAt);

        long ragStartedAt = System.nanoTime();
        List<VectorSearchResult> retrievedKnowledge = ragRetrievalService.retrieve(
                session.getId(),
                session.getUserKey(),
                session.getActiveDocumentId(),
                retrievalQuestion,
                privateMode
        );
        long ragMs = elapsedMillis(ragStartedAt);

        String prompt = promptBuilder.buildChatPrompt(
                message,
                retrievalQuestion,
                memory,
                session.getActiveDocumentName(),
                storedAnswer,
                retrievedKnowledge,
                privateMode
        );
        log.info(
                "chat request prepared requestId={} privateMode={} activeDocumentId={} activeDocumentName={} dbHit={} retrievedChunks={} promptChars={} sessionMs={} memoryMs={} dbMs={} ragMs={} prepMs={}",
                requestId,
                privateMode,
                session.getActiveDocumentId(),
                session.getActiveDocumentName(),
                storedAnswer != null,
                retrievedKnowledge.size(),
                prompt.length(),
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
                hasGrounding(storedAnswer, retrievedKnowledge),
                false
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
                "chat request completed requestId={} stream={} retrievedChunks={} llmMs={} totalMs={}",
                prepared.requestId(),
                stream,
                prepared.retrievedKnowledge().size(),
                elapsedMillis(llmStartedAt),
                elapsedMillis(prepared.startedAt())
        );
        return new ChatResponse(
                reply,
                prepared.privateMode(),
                sourceReferences(prepared.retrievedKnowledge())
        );
    }

    private ChatResponse noGroundingResponse(
            PreparedChat prepared,
            long llmStartedAt,
            boolean stream,
            Consumer<String> onToken
    ) {
        if (onToken != null) {
            onToken.accept(NOT_FOUND_REPLY);
        }
        sessionService.saveMessage(prepared.sessionId(), "assistant", NOT_FOUND_REPLY, prepared.privateMode());
        log.info(
                "chat request completed requestId={} stream={} retrievedChunks=0 llmSkipped=true totalMs={}",
                prepared.requestId(),
                stream,
                elapsedMillis(prepared.startedAt())
        );
        return response(prepared, NOT_FOUND_REPLY, llmStartedAt, stream);
    }

    private List<SourceReferenceResponse> sourceReferences(List<VectorSearchResult> retrievedKnowledge) {
        Map<String, SourceReferenceResponse> references = new LinkedHashMap<>();
        for (VectorSearchResult result : retrievedKnowledge) {
            if (result.sourceName() == null || result.sourceName().isBlank()) {
                continue;
            }
            String key = result.sourceName() + "|" + result.pageNumber() + "|" + result.sectionTitle();
            references.putIfAbsent(key, new SourceReferenceResponse(
                    result.sourceName(),
                    defaultString(result.documentType()),
                    result.pageNumber(),
                    defaultString(result.sectionTitle()),
                    Math.round(result.score() * 1000.0) / 1000.0
            ));
            if (references.size() >= 5) {
                break;
            }
        }
        return List.copyOf(references.values());
    }

    private ChatResponse indexingResponse(
            PreparedChat prepared,
            long llmStartedAt,
            boolean stream,
            Consumer<String> onToken
    ) {
        if (onToken != null) {
            onToken.accept(INDEXING_REPLY);
        }
        log.info(
                "chat request blocked requestId={} stream={} reason=ingestion-active totalMs={}",
                prepared.requestId(),
                stream,
                elapsedMillis(prepared.startedAt())
        );
        return response(prepared, INDEXING_REPLY, llmStartedAt, stream);
    }

    private boolean hasGrounding(String storedAnswer, List<VectorSearchResult> retrievedKnowledge) {
        return storedAnswer != null && !storedAnswer.isBlank() || !retrievedKnowledge.isEmpty();
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
            boolean hasGrounding,
            boolean indexingActive
    ) {
    }
}
