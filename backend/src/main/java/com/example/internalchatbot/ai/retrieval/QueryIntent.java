package com.example.internalchatbot.ai.retrieval;

import com.example.internalchatbot.ai.vectorstore.RetrievalFilter;

public record QueryIntent(
        String name,
        double confidence,
        RetrievalFilter filter
) {
    public boolean confident(double threshold) {
        return confidence >= threshold && filter != null && !filter.empty();
    }
}
