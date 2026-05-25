package com.example.internalchatbot.ai.retrieval;

public record RetrievalScope(
        String sessionId,
        String userKey,
        Long activeDocumentId
) {
    public boolean documentScoped() {
        return activeDocumentId != null;
    }

    public boolean sessionScoped() {
        return sessionId != null && !sessionId.isBlank();
    }

    public boolean userScoped() {
        return userKey != null && !userKey.isBlank();
    }
}
