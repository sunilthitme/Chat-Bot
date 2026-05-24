package com.example.internalchatbot.ai.vectorstore;

import java.util.Set;

public record RetrievalFilter(
        Set<String> documentTypes,
        Set<String> languages,
        Set<String> topics,
        boolean enforce
) {
    public static RetrievalFilter none() {
        return new RetrievalFilter(Set.of(), Set.of(), Set.of(), false);
    }

    public static RetrievalFilter boostOnly(Set<String> documentTypes, Set<String> languages, Set<String> topics) {
        return new RetrievalFilter(documentTypes, languages, topics, false);
    }

    public static RetrievalFilter enforced(Set<String> documentTypes, Set<String> languages, Set<String> topics) {
        return new RetrievalFilter(documentTypes, languages, topics, true);
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
