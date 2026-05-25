package com.example.internalchatbot.ai.retrieval;

import com.example.internalchatbot.ai.vectorstore.VectorSearchResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalConfidenceServiceTest {

    private final RetrievalConfidenceService service = new RetrievalConfidenceService(0.62, 0.42);

    @Test
    void treatsGreetingAsGeneralConversation() {
        RetrievalDecision decision = service.evaluate("Hi", null, List.of());

        assertThat(decision.grounded()).isFalse();
        assertThat(decision.reason()).isEqualTo("conversational-turn");
        assertThat(service.shouldAttemptRetrieval("Hi")).isFalse();
    }

    @Test
    void trustsStoredAnswerAsGroundedKnowledge() {
        RetrievalDecision decision = service.evaluate("How to create RITM?", "Use the service portal.", List.of());

        assertThat(decision.grounded()).isTrue();
        assertThat(decision.confidence()).isEqualTo(1.0);
        assertThat(decision.reason()).isEqualTo("stored-answer");
    }

    @Test
    void acceptsRelevantRetrievedKnowledgeAboveThreshold() {
        VectorSearchResult result = new VectorSearchResult(
                1L,
                10L,
                0,
                "guide.txt",
                "file",
                null,
                1,
                "Password reset",
                "knowledge",
                "text",
                "support",
                "Steps to reset password include opening the login page and using Forgot Password.",
                0.82
        );

        RetrievalDecision decision = service.evaluate("How do I reset password?", null, List.of(result));

        assertThat(decision.grounded()).isTrue();
        assertThat(decision.reason()).isEqualTo("relevant-retrieval");
    }
}
