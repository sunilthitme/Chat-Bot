package com.example.internalchatbot.ai.retrieval;

import com.example.internalchatbot.ai.vectorstore.VectorSearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RetrievalConfidenceService {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "for", "from",
            "how", "i", "in", "is", "it", "me", "my", "of", "on", "or", "please",
            "the", "to", "what", "when", "where", "who", "why", "with", "you", "your"
    );

    private static final Set<String> LIGHTWEIGHT_CHAT = Set.of(
            "hi", "hello", "hey", "ok", "okay", "thanks", "thank you", "bye", "goodbye", "yo"
    );

    private final double confidenceThreshold;
    private final double minimumTopScore;

    public RetrievalConfidenceService(
            @Value("${rag.answer-confidence-threshold:0.62}") double confidenceThreshold,
            @Value("${rag.minimum-top-score:0.42}") double minimumTopScore
    ) {
        this.confidenceThreshold = clamp(confidenceThreshold, 0.1, 0.99);
        this.minimumTopScore = clamp(minimumTopScore, 0.05, 0.99);
    }

    public RetrievalDecision evaluate(
            String userQuestion,
            String storedAnswer,
            List<VectorSearchResult> retrievedKnowledge
    ) {
        if (storedAnswer != null && !storedAnswer.isBlank()) {
            return new RetrievalDecision(true, 1.0, 1.0, 1.0, "stored-answer");
        }

        if (looksLikeLightweightChat(userQuestion)) {
            return new RetrievalDecision(false, 0, 0, 0, "conversational-turn");
        }

        if (retrievedKnowledge == null || retrievedKnowledge.isEmpty()) {
            return new RetrievalDecision(false, 0, 0, 0, "no-retrieval-results");
        }

        double topScore = retrievedKnowledge.stream()
                .mapToDouble(VectorSearchResult::score)
                .max()
                .orElse(0);
        double averageTopScore = retrievedKnowledge.stream()
                .limit(3)
                .mapToDouble(VectorSearchResult::score)
                .average()
                .orElse(0);
        double lexicalCoverage = lexicalCoverage(userQuestion, retrievedKnowledge);
        double normalizedTopScore = clamp(topScore, 0, 1);
        double normalizedAverageScore = clamp(averageTopScore, 0, 1);
        double confidence = clamp(
                (normalizedTopScore * 0.68) + (normalizedAverageScore * 0.20) + (lexicalCoverage * 0.12),
                0,
                1
        );
        boolean grounded = topScore >= minimumTopScore && confidence >= confidenceThreshold;
        String reason = grounded ? "relevant-retrieval" : "low-retrieval-confidence";
        return new RetrievalDecision(grounded, round(confidence), round(topScore), round(lexicalCoverage), reason);
    }

    public boolean shouldAttemptRetrieval(String userQuestion) {
        return !looksLikeLightweightChat(userQuestion) && tokens(userQuestion).size() >= 1;
    }

    private double lexicalCoverage(String userQuestion, List<VectorSearchResult> retrievedKnowledge) {
        Set<String> queryTokens = tokens(userQuestion);
        if (queryTokens.isEmpty()) {
            return 0;
        }
        String retrievedText = retrievedKnowledge.stream()
                .limit(3)
                .map(VectorSearchResult::content)
                .collect(Collectors.joining(" "))
                .toLowerCase(Locale.ROOT);
        long matches = queryTokens.stream()
                .filter(retrievedText::contains)
                .count();
        return matches / (double) queryTokens.size();
    }

    private Set<String> tokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(text.toLowerCase(Locale.ROOT).split("\\s+"))
                .map(token -> token.replaceAll("[^a-z0-9_./-]", ""))
                .filter(token -> token.length() >= 3)
                .filter(token -> !STOP_WORDS.contains(token))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean looksLikeLightweightChat(String text) {
        if (text == null) {
            return true;
        }
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        return LIGHTWEIGHT_CHAT.contains(normalized) || normalized.length() <= 2;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
