package com.example.internalchatbot.ai.vectorstore;

import java.util.Set;

public record RetrievalFilter(
        Set<String> documentTypes,
        Set<String> languages,
        Set<String> topics
) {
    public static RetrievalFilter none() {
        return new RetrievalFilter(Set.of(), Set.of(), Set.of());
    }

    public boolean empty() {
        return documentTypes.isEmpty() && languages.isEmpty() && topics.isEmpty();
    }

    public boolean matches(String documentType, String language, String topic) {
        return matchesAny(documentTypes, documentType)
                && matchesAny(languages, language)
                && matchesAny(topics, topic);
    }

    private boolean matchesAny(Set<String> allowed, String value) {
        return allowed.isEmpty() || allowed.contains(normalize(value));
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase();
    }
}
