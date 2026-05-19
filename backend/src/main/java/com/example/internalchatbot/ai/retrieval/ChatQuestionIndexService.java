package com.example.internalchatbot.ai.retrieval;

import com.example.internalchatbot.entity.ChatQuestion;
import com.example.internalchatbot.entity.ChatQuestionToken;
import com.example.internalchatbot.repository.ChatQuestionRepository;
import com.example.internalchatbot.repository.ChatQuestionTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ChatQuestionIndexService {

    private static final Logger log = LoggerFactory.getLogger(ChatQuestionIndexService.class);
    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "for", "from", "how",
            "i", "in", "is", "it", "me", "my", "of", "on", "or", "please", "the", "to",
            "what", "when", "where", "who", "why", "with", "you", "your"
    );

    private final ChatQuestionRepository chatQuestionRepository;
    private final ChatQuestionTokenRepository chatQuestionTokenRepository;
    private volatile boolean indexReady;

    public ChatQuestionIndexService(
            ChatQuestionRepository chatQuestionRepository,
            ChatQuestionTokenRepository chatQuestionTokenRepository
    ) {
        this.chatQuestionRepository = chatQuestionRepository;
        this.chatQuestionTokenRepository = chatQuestionTokenRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public synchronized void rebuildMissingIndex() {
        if (indexReady) {
            return;
        }

        long startedAt = System.nanoTime();
        int indexed = 0;
        Pageable pageable = PageRequest.of(0, 100);
        Page<ChatQuestion> page;

        do {
            page = chatQuestionRepository.findAll(pageable);
            for (ChatQuestion question : page.getContent()) {
                if (ensureIndexed(question)) {
                    indexed++;
                }
            }
            pageable = pageable.next();
        } while (page.hasNext());

        log.info("chat question search index ready indexed={} totalMs={}", indexed, elapsedMillis(startedAt));
        indexReady = true;
    }

    @Transactional
    public List<ChatQuestion> search(String normalizedMessage, int limit) {
        if (normalizedMessage == null || normalizedMessage.isBlank()) {
            return List.of();
        }
        if (!indexReady) {
            rebuildMissingIndex();
        }

        int safeLimit = Math.max(1, Math.min(limit, 10));
        List<ChatQuestion> exactOrPrefix = chatQuestionRepository.searchExactOrPrefix(
                normalizedMessage,
                PageRequest.of(0, safeLimit)
        );
        if (!exactOrPrefix.isEmpty()) {
            return exactOrPrefix;
        }

        List<String> tokens = tokenize(normalizedMessage);
        if (tokens.isEmpty()) {
            return List.of();
        }

        List<Long> rankedIds = chatQuestionTokenRepository.findRankedQuestionIds(
                tokens.stream().limit(8).toList(),
                PageRequest.of(0, safeLimit)
        );
        if (rankedIds.isEmpty()) {
            return List.of();
        }

        Map<Long, ChatQuestion> byId = new LinkedHashMap<>();
        chatQuestionRepository.findAllById(rankedIds)
                .forEach(question -> byId.put(question.getId(), question));

        List<ChatQuestion> ordered = new ArrayList<>();
        for (Long rankedId : rankedIds) {
            ChatQuestion question = byId.get(rankedId);
            if (question != null) {
                ordered.add(question);
            }
        }
        return ordered;
    }

    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (String token : text.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+")) {
            if (token.length() < 3 || STOP_WORDS.contains(token)) {
                continue;
            }
            tokens.add(token.length() > 80 ? token.substring(0, 80) : token);
        }
        return List.copyOf(tokens);
    }

    private boolean ensureIndexed(ChatQuestion question) {
        boolean normalizedChanged = question.refreshSearchFields();
        boolean missingTokens = question.getId() != null
                && !chatQuestionTokenRepository.existsByQuestionId(question.getId());

        if (normalizedChanged) {
            chatQuestionRepository.save(question);
        }

        if (!missingTokens && !normalizedChanged) {
            return false;
        }

        chatQuestionTokenRepository.deleteByQuestionId(question.getId());
        chatQuestionTokenRepository.saveAll(toTokens(question));
        return true;
    }

    private Collection<ChatQuestionToken> toTokens(ChatQuestion question) {
        List<String> tokens = tokenize(question.getQuestion() + " " + question.getKeywords());
        return tokens.stream()
                .map(token -> new ChatQuestionToken(question.getId(), token))
                .toList();
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
