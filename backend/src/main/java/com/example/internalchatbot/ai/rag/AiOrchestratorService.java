package com.example.internalchatbot.ai.rag;

import com.example.internalchatbot.ai.ingestion.KnowledgeIngestionService;
import com.example.internalchatbot.ai.llm.LlmService;
import com.example.internalchatbot.ai.memory.ConversationMemoryService;
import com.example.internalchatbot.ai.memory.MemoryManagerService;
import com.example.internalchatbot.ai.memory.SessionService;
import com.example.internalchatbot.ai.prompts.PromptBuilder;
import com.example.internalchatbot.ai.retrieval.ChatQuestionIndexService;
import com.example.internalchatbot.ai.retrieval.RagRetrievalService;
import com.example.internalchatbot.ai.retrieval.RetrievalConfidenceService;
import com.example.internalchatbot.ai.retrieval.RetrievalDecision;
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

    private final ChatQuestionIndexService chatQuestionIndexService;
    private final LlmService llmService;
    private final SessionService sessionService;
    private final ConversationMemoryService conversationMemoryService;
    private final MemoryManagerService memoryManagerService;
    private final KnowledgeIngestionService knowledgeIngestionService;
    private final RagRetrievalService ragRetrievalService;
    private final RetrievalConfidenceService retrievalConfidenceService;
    private final PromptBuilder promptBuilder;
    private final int internalSearchLimit;
    private final Map<String, Optional<String>> answerCache;

    public AiOrchestratorService(
            ChatQuestionIndexService chatQuestionIndexService,
            LlmService llmService,
            SessionService sessionService,
            ConversationMemoryService conversationMemoryService,
            MemoryManagerService memoryManagerService,
            KnowledgeIngestionService knowledgeIngestionService,
            RagRetrievalService ragRetrievalService,
            RetrievalConfidenceService retrievalConfidenceService,
            PromptBuilder promptBuilder,
            @Value("${chat.internal-search-limit:5}") int internalSearchLimit,
            @Value("${chat.answer-cache-size:256}") int answerCacheSize
    ) {
        this.chatQuestionIndexService = chatQuestionIndexService;
        this.llmService = llmService;
        this.sessionService = sessionService;
        this.conversationMemoryService = conversationMemoryService;
        this.memoryManagerService = memoryManagerService;
        this.knowledgeIngestionService = knowledgeIngestionService;
        this.ragRetrievalService = ragRetrievalService;
        this.retrievalConfidenceService = retrievalConfidenceService;
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
        String reply = generateReply(
                prepared.prompt(),
                prepared.storedAnswer(),
                prepared.requestId(),
                fallbackReply(prepared)
        );
        persistAssistantTurn(prepared, reply);

        log.info(
                "chat request completed requestId={} stream=false mode={} retrievedChunks={} confidence={} llmMs={} totalMs={}",
                prepared.requestId(),
                prepared.responseMode(),
                prepared.retrievedKnowledge().size(),
                prepared.retrievalDecision().confidence(),
                elapsedMillis(llmStartedAt),
                elapsedMillis(prepared.startedAt())
        );

        return response(prepared, reply, llmStartedAt, false);
    }

    public ChatResponse stream(ChatRequest request, Consumer<String> onToken) {
        PreparedChat prepared = prepare(request);
        long llmStartedAt = System.nanoTime();

        if (!llmService.isEnabled()) {
            String fallback = fallbackReply(prepared);
            onToken.accept(fallback);
            persistAssistantTurn(prepared, fallback);
            return response(prepared, fallback, llmStartedAt, true);
        }

        StringBuilder streamedReply = new StringBuilder();
        try {
            llmService.streamResponse(prepared.prompt(), token -> {
                streamedReply.append(token);
                onToken.accept(token);
            });
        } catch (RestClientException ex) {
            log.warn("Ollama streaming failed requestId={}. Falling back gracefully.", prepared.requestId(), ex);
        }

        String reply = streamedReply.isEmpty()
                ? fallbackReply(prepared)
                : streamedReply.toString();
        if (streamedReply.isEmpty()) {
            onToken.accept(reply);
        }
        persistAssistantTurn(prepared, reply);
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
        boolean indexingActive = !privateMode && knowledgeIngestionService.hasActiveIngestion(session.getId());
        sessionService.saveMessage(session.getId(), "user", message, privateMode);
        long sessionMs = elapsedMillis(sessionStartedAt);

        long memoryStartedAt = System.nanoTime();
        String conversationMemory = conversationMemoryService.loadMemory(session.getId(), privateMode);
        String longTermMemory = memoryManagerService.recallRelevantMemory(
                session.getUserKey(),
                session.getId(),
                message,
                privateMode
        );
        String retrievalQuestion = conversationMemoryService.buildRetrievalQuery(
                message,
                combineMemory(conversationMemory, longTermMemory)
        );
        long memoryMs = elapsedMillis(memoryStartedAt);

        long dbStartedAt = System.nanoTime();
        String normalizedMessage = normalize(message);
        boolean retrievalCandidate = retrievalConfidenceService.shouldAttemptRetrieval(message);
        String storedAnswer = retrievalCandidate ? findStoredAnswer(normalizedMessage, requestId) : null;
        long dbMs = elapsedMillis(dbStartedAt);

        long ragStartedAt = System.nanoTime();
        List<VectorSearchResult> retrievedKnowledge = retrievalCandidate
                ? ragRetrievalService.retrieve(
                        session.getId(),
                        session.getUserKey(),
                        session.getActiveDocumentId(),
                        message,
                        retrievalQuestion,
                        privateMode
                )
                : List.of();
        long ragMs = elapsedMillis(ragStartedAt);

        RetrievalDecision retrievalDecision = retrievalConfidenceService.evaluate(message, storedAnswer, retrievedKnowledge);
        String responseMode = retrievalDecision.grounded()
                ? (storedAnswer == null ? "rag" : "stored-rag")
                : "general";
        String prompt = retrievalDecision.grounded()
                ? promptBuilder.buildRagPrompt(
                        message,
                        retrievalQuestion,
                        conversationMemory,
                        longTermMemory,
                        session.getActiveDocumentName(),
                        storedAnswer,
                        retrievedKnowledge,
                        privateMode,
                        retrievalDecision.confidence()
                )
                : promptBuilder.buildGeneralPrompt(
                        message,
                        conversationMemory,
                        longTermMemory,
                        session.getActiveDocumentName(),
                        privateMode,
                        indexingActive,
                        retrievalDecision.confidence()
                );
        log.info(
                "chat request prepared requestId={} privateMode={} mode={} activeDocumentId={} activeDocumentName={} dbHit={} retrievedChunks={} confidence={} decisionReason={} promptChars={} sessionMs={} memoryMs={} dbMs={} ragMs={} prepMs={}",
                requestId,
                privateMode,
                responseMode,
                session.getActiveDocumentId(),
                session.getActiveDocumentName(),
                storedAnswer != null,
                retrievedKnowledge.size(),
                retrievalDecision.confidence(),
                retrievalDecision.reason(),
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
                session.getUserKey(),
                privateMode,
                message,
                storedAnswer,
                retrievedKnowledge,
                prompt,
                retrievalDecision,
                responseMode,
                retrievalDecision.grounded(),
                indexingActive
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

    private String generateReply(String prompt, String storedAnswer, String requestId, String fallbackReply) {
        if (!llmService.isEnabled()) {
            return storedAnswer == null ? fallbackReply : storedAnswer;
        }

        try {
            String llmReply = llmService.generateResponse(prompt);
            if (!llmReply.isBlank()) {
                return llmReply;
            }
        } catch (RuntimeException ex) {
            log.warn("Ollama request failed requestId={}. Falling back gracefully.", requestId, ex);
        }

        return storedAnswer == null ? fallbackReply : storedAnswer;
    }

    private ChatResponse response(PreparedChat prepared, String reply, long llmStartedAt, boolean stream) {
        log.info(
                "chat request completed requestId={} stream={} mode={} retrievedChunks={} confidence={} llmMs={} totalMs={}",
                prepared.requestId(),
                stream,
                prepared.responseMode(),
                prepared.retrievedKnowledge().size(),
                prepared.retrievalDecision().confidence(),
                elapsedMillis(llmStartedAt),
                elapsedMillis(prepared.startedAt())
        );
        return new ChatResponse(
                reply,
                prepared.privateMode(),
                sourceReferences(prepared.grounded() ? prepared.retrievedKnowledge() : List.of()),
                prepared.responseMode(),
                prepared.retrievalDecision().confidence()
        );
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

    private void persistAssistantTurn(PreparedChat prepared, String reply) {
        sessionService.saveMessage(prepared.sessionId(), "assistant", reply, prepared.privateMode());
        memoryManagerService.rememberTurnAsync(
                prepared.sessionId(),
                prepared.userKey(),
                prepared.message(),
                reply,
                prepared.privateMode()
        );
    }

    private String fallbackReply(PreparedChat prepared) {
        String normalized = normalize(prepared.message());
        if (isGreeting(normalized)) {
            return "Hello! How can I help you today?";
        }
        if (isThanks(normalized)) {
            return "You're welcome. What would you like to work on next?";
        }
        if (prepared.indexingActive() && containsAny(normalized, "document", "upload", "file", "pdf", "indexed")) {
            return "Some uploaded content is still being indexed. I can answer from it once indexing completes, or I can help generally if you share the relevant details here.";
        }
        return "I do not have enough relevant indexed context for that, and Ollama is currently unavailable. Once Ollama is running, I can answer generally or use matching uploaded knowledge.";
    }

    private String combineMemory(String conversationMemory, String longTermMemory) {
        if ((conversationMemory == null || conversationMemory.isBlank())
                && (longTermMemory == null || longTermMemory.isBlank())) {
            return "";
        }
        return "Recent conversation:\n"
                + defaultString(conversationMemory)
                + "\nLong-term memory:\n"
                + defaultString(longTermMemory);
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private boolean isGreeting(String normalized) {
        return normalized.equals("hi")
                || normalized.equals("hello")
                || normalized.equals("hey")
                || normalized.equals("good morning")
                || normalized.equals("good afternoon")
                || normalized.equals("good evening");
    }

    private boolean isThanks(String normalized) {
        return normalized.equals("thanks")
                || normalized.equals("thank you")
                || normalized.equals("thx")
                || normalized.equals("ty");
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
            String userKey,
            boolean privateMode,
            String message,
            String storedAnswer,
            List<VectorSearchResult> retrievedKnowledge,
            String prompt,
            RetrievalDecision retrievalDecision,
            String responseMode,
            boolean grounded,
            boolean indexingActive
    ) {
    }
}
