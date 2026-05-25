package com.example.internalchatbot.ai.memory;

import com.example.internalchatbot.ai.embeddings.EmbeddingService;
import com.example.internalchatbot.ai.ingestion.ContentHash;
import com.example.internalchatbot.ai.vectorstore.VectorStoreService;
import com.example.internalchatbot.entity.EmbeddingMetadata;
import com.example.internalchatbot.entity.UserMemory;
import com.example.internalchatbot.repository.UserMemoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class MemoryManagerService {

    private static final Logger log = LoggerFactory.getLogger(MemoryManagerService.class);
    private static final String DEFAULT_USER = "local-user";
    private static final Pattern MY_NAME_PATTERN = Pattern.compile("\\bmy name is\\s+([A-Za-z][A-Za-z .'-]{1,80})", Pattern.CASE_INSENSITIVE);
    private static final Pattern CALL_ME_PATTERN = Pattern.compile("\\bcall me\\s+([A-Za-z][A-Za-z .'-]{1,80})", Pattern.CASE_INSENSITIVE);
    private static final Pattern I_PREFER_PATTERN = Pattern.compile("\\bi prefer\\s+(.{3,180})", Pattern.CASE_INSENSITIVE);
    private static final Pattern WE_USE_PATTERN = Pattern.compile("\\b(?:we|our team|our project) use(?:s)?\\s+(.{3,220})", Pattern.CASE_INSENSITIVE);

    private static final Set<String> TRIVIAL_MESSAGES = Set.of(
            "hi", "hello", "hey", "ok", "okay", "thanks", "thank you", "thx", "yes", "no", "bye", "goodbye"
    );

    private static final Set<String> TECHNICAL_TERMS = Set.of(
            "java", "spring", "spring boot", "angular", "ollama", "phi3", "h2", "lucene",
            "rag", "embedding", "embeddings", "database", "api", "controller", "service",
            "repository", "architecture", "private mode", "memory", "session", "document",
            "upload", "streaming", "fallback", "timeout"
    );

    private final UserMemoryRepository userMemoryRepository;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final AsyncTaskExecutor ingestionTaskExecutor;
    private final int recallLimit;
    private final int maxRecallChars;

    public MemoryManagerService(
            UserMemoryRepository userMemoryRepository,
            EmbeddingService embeddingService,
            VectorStoreService vectorStoreService,
            @Qualifier("ingestionTaskExecutor") AsyncTaskExecutor ingestionTaskExecutor,
            @Value("${chat.memory.long-term-limit:8}") int recallLimit,
            @Value("${chat.memory.long-term-max-chars:1200}") int maxRecallChars
    ) {
        this.userMemoryRepository = userMemoryRepository;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.ingestionTaskExecutor = ingestionTaskExecutor;
        this.recallLimit = Math.max(2, Math.min(recallLimit, 20));
        this.maxRecallChars = Math.max(300, maxRecallChars);
    }

    public String recallRelevantMemory(String userKey, String sessionId, String userQuestion, boolean privateMode) {
        if (privateMode) {
            log.info("memory recall skipped sessionId={} reason=private-mode", safe(sessionId));
            return "";
        }

        String normalizedUserKey = normalizeUserKey(userKey);
        List<ScoredMemory> scored = new ArrayList<>();
        userMemoryRepository.findByUserKeyOrderByImportanceScoreDescUpdatedAtDesc(
                        normalizedUserKey,
                        PageRequest.of(0, Math.max(20, recallLimit * 4))
                )
                .stream()
                .map(memory -> score(memory, userQuestion, sessionId))
                .filter(memory -> memory.score() > 0.05)
                .forEach(scored::add);

        if (sessionId != null && !sessionId.isBlank()) {
            userMemoryRepository.findBySessionIdOrderByUpdatedAtDesc(sessionId, PageRequest.of(0, 12))
                    .stream()
                    .map(memory -> score(memory, userQuestion, sessionId))
                    .filter(memory -> memory.score() > 0.05)
                    .forEach(scored::add);
        }

        Map<Long, ScoredMemory> deduped = new LinkedHashMap<>();
        for (ScoredMemory memory : scored) {
            if (memory.memory().getId() == null) {
                continue;
            }
            deduped.merge(
                    memory.memory().getId(),
                    memory,
                    (left, right) -> left.score() >= right.score() ? left : right
            );
        }

        List<ScoredMemory> selected = deduped.values()
                .stream()
                .sorted(Comparator.comparingDouble(ScoredMemory::score).reversed())
                .limit(recallLimit)
                .toList();

        StringBuilder builder = new StringBuilder();
        selected.forEach(memory -> {
                    if (builder.length() < maxRecallChars) {
                        builder.append("- ")
                                .append(memory.memory().getMemoryType())
                                .append(": ")
                                .append(memory.memory().getContent())
                                .append('\n');
                    }
                });
        log.info(
                "memory recall completed sessionId={} userKey={} candidates={} selected={} chars={}",
                safe(sessionId),
                safe(normalizedUserKey),
                deduped.size(),
                selected.size(),
                builder.length()
        );
        return trim(builder.toString(), maxRecallChars);
    }

    public void rememberTurnAsync(
            String sessionId,
            String userKey,
            String userMessage,
            String assistantResponse,
            boolean privateMode
    ) {
        if (privateMode) {
            return;
        }
        ingestionTaskExecutor.execute(() -> rememberTurn(sessionId, userKey, userMessage, assistantResponse));
    }

    private void rememberTurn(String sessionId, String userKey, String userMessage, String assistantResponse) {
        List<MemoryCandidate> candidates = extractMemoryCandidates(userMessage, assistantResponse);
        if (candidates.isEmpty()) {
            log.debug("memory storage skipped sessionId={} reason=no-meaningful-candidates", safe(sessionId));
            return;
        }

        String normalizedUserKey = normalizeUserKey(userKey);
        for (MemoryCandidate candidate : candidates) {
            try {
                String contentHash = ContentHash.sha256(candidate.content());
                Optional<UserMemory> existing = userMemoryRepository.findFirstByUserKeyAndContentHash(normalizedUserKey, contentHash);
                UserMemory memory = existing.orElseGet(UserMemory::new);
                memory.setUserKey(normalizedUserKey);
                memory.setSessionId(sessionId);
                memory.setMemoryType(candidate.type());
                memory.setContent(candidate.content());
                memory.setContentHash(contentHash);
                memory.setImportanceScore(Math.max(memory.getImportanceScore(), candidate.importance()));
                UserMemory saved = userMemoryRepository.save(memory);
                ensureMemoryEmbedding(saved);
                log.info(
                        "memory stored sessionId={} userKey={} type={} importance={} hasEmbedding={}",
                        safe(sessionId),
                        safe(normalizedUserKey),
                        candidate.type(),
                        candidate.importance(),
                        saved.getEmbeddingId() != null
                );
            } catch (RuntimeException ex) {
                log.warn("Unable to persist long-term memory for sessionId={}", safe(sessionId), ex);
            }
        }
    }

    private void ensureMemoryEmbedding(UserMemory memory) {
        if (memory.getEmbeddingId() != null) {
            return;
        }
        List<Double> vector = embeddingService.embed(memory.getContent());
        if (vector.isEmpty()) {
            return;
        }
        EmbeddingMetadata embedding = vectorStoreService.store(
                "memory",
                memory.getSessionId(),
                memory.getUserKey(),
                null,
                "Long-term memory",
                "memory",
                memory.getContent(),
                vector,
                false
        );
        memory.setEmbeddingId(embedding.getId());
        userMemoryRepository.save(memory);
    }

    private List<MemoryCandidate> extractMemoryCandidates(String userMessage, String assistantResponse) {
        String normalized = normalizeText(userMessage);
        if (normalized.isBlank() || TRIVIAL_MESSAGES.contains(normalized)) {
            return List.of();
        }

        List<MemoryCandidate> candidates = new ArrayList<>();
        addPatternMemory(candidates, MY_NAME_PATTERN, userMessage, "personal_fact", "User's name is %s.", 0.95);
        addPatternMemory(candidates, CALL_ME_PATTERN, userMessage, "preference", "User prefers to be called %s.", 0.92);
        addPatternMemory(candidates, I_PREFER_PATTERN, userMessage, "preference", "User prefers %s.", 0.86);
        addPatternMemory(candidates, WE_USE_PATTERN, userMessage, "technical_context", "Project/team uses %s.", 0.84);

        if (!candidates.isEmpty()) {
            return candidates.stream().map(this::cleanCandidate).toList();
        }

        if (!isMeaningfulForLongTermMemory(normalized)) {
            return List.of();
        }

        String type = inferMemoryType(normalized);
        double importance = switch (type) {
            case "preference", "personal_fact" -> 0.85;
            case "technical_context", "project_context" -> 0.78;
            default -> 0.62;
        };
        candidates.add(new MemoryCandidate(type, "Important context from user: " + trim(userMessage.trim(), 500), importance));
        return candidates.stream().map(this::cleanCandidate).toList();
    }

    private void addPatternMemory(
            List<MemoryCandidate> candidates,
            Pattern pattern,
            String text,
            String type,
            String template,
            double importance
    ) {
        if (text == null || text.isBlank()) {
            return;
        }
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return;
        }
        String captured = matcher.group(1)
                .replaceAll("[.?!]+$", "")
                .trim();
        candidates.add(new MemoryCandidate(type, template.formatted(captured), importance));
    }

    private MemoryCandidate cleanCandidate(MemoryCandidate candidate) {
        return new MemoryCandidate(
                candidate.type(),
                trim(candidate.content().replaceAll("\\s+", " ").trim(), 700),
                candidate.importance()
        );
    }

    private boolean isMeaningfulForLongTermMemory(String normalized) {
        if (normalized.length() < 12) {
            return false;
        }
        boolean firstPersonOrTeamContext = containsAny(
                normalized,
                "my ", "i am", "i'm", "i work", "i use", "i need", "remember",
                "we use", "our ", "project", "requirement", "current issue", "final goal"
        );
        boolean technical = TECHNICAL_TERMS.stream().anyMatch(normalized::contains);
        boolean directQuestionOnly = normalized.endsWith("?")
                && !firstPersonOrTeamContext
                && !containsAny(normalized, "requirement", "issue", "bug", "error");
        return !directQuestionOnly && (firstPersonOrTeamContext || technical);
    }

    private String inferMemoryType(String normalized) {
        if (containsAny(normalized, "prefer", "like to", "call me")) {
            return "preference";
        }
        if (containsAny(normalized, "my name", "i am", "i work", "i live")) {
            return "personal_fact";
        }
        if (containsAny(normalized, "requirement", "current issue", "final goal", "project")) {
            return "project_context";
        }
        if (TECHNICAL_TERMS.stream().anyMatch(normalized::contains)) {
            return "technical_context";
        }
        return "conversation_context";
    }

    private ScoredMemory score(UserMemory memory, String question, String sessionId) {
        Set<String> queryTokens = tokens(question);
        String content = normalizeText(memory.getContent());
        long matches = queryTokens.stream().filter(content::contains).count();
        double lexical = queryTokens.isEmpty() ? 0.15 : matches / (double) queryTokens.size();
        double sessionBoost = sessionId != null && sessionId.equals(memory.getSessionId()) ? 0.20 : 0;
        double importance = Math.min(1.0, memory.getImportanceScore());
        return new ScoredMemory(memory, (lexical * 0.58) + (importance * 0.24) + sessionBoost);
    }

    private Set<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(token -> token.replaceAll("[^a-z0-9_./-]", ""))
                .filter(token -> token.length() >= 3)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean containsAny(String text, String... needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeText(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT)
                        .replaceAll("[^a-z0-9_./?'-]+", " ")
                        .replaceAll("\\s+", " ")
                        .trim();
    }

    private String normalizeUserKey(String userKey) {
        return userKey == null || userKey.isBlank() ? DEFAULT_USER : userKey.trim().toLowerCase(Locale.ROOT);
    }

    private String trim(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private record MemoryCandidate(String type, String content, double importance) {
    }

    private record ScoredMemory(UserMemory memory, double score) {
    }
}
