package com.example.internalchatbot.ai.retrieval;

public record RetrievalDecision(
        boolean grounded,
        double confidence,
        double topScore,
        double lexicalCoverage,
        String reason
) {
}
