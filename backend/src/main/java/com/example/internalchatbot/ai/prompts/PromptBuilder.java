package com.example.internalchatbot.ai.prompts;

import com.example.internalchatbot.ai.vectorstore.VectorSearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class PromptBuilder {

    private final int maxContextChars;
    private final int maxChunkChars;

    public PromptBuilder(
            @Value("${rag.max-context-chars:3000}") int maxContextChars,
            @Value("${rag.max-chunk-context-chars:850}") int maxChunkChars
    ) {
        this.maxContextChars = Math.max(1_200, maxContextChars);
        this.maxChunkChars = Math.max(300, maxChunkChars);
    }

    public String buildChatPrompt(
            String message,
            String retrievalQuestion,
            String memory,
            String storedAnswer,
            List<VectorSearchResult> retrievedKnowledge,
            boolean privateMode
    ) {
        String knowledge = formatKnowledge(retrievedKnowledge);

        return """
                You are a strict RAG assistant.

                Grounding rules:
                1. Answer ONLY from the Internal DB answer and Retrieved context below.
                2. Do NOT use pretrained knowledge, world knowledge, guesses, or assumptions.
                3. Do NOT add dates, facts, examples, or explanations that are not present in the grounding data.
                4. If the grounding data does not contain the answer, reply exactly: "Information not found in the indexed knowledge."
                5. If the grounding data partially answers the question, answer only the supported part and say what is not found.
                6. Never mention sources, URLs, citations, vector IDs, embedding IDs, database IDs, session IDs, or internal metadata.
                7. Use session memory only to resolve pronouns or follow-up wording. Session memory is not a factual source.
                8. Keep the answer concise and natural.

                Session memory for follow-up resolution:
                %s

                Retrieval query:
                %s

                Internal DB answer (grounding source):
                %s

                Retrieved context (grounding source):
                %s

                User question:
                %s
                """.formatted(
                memory == null || memory.isBlank() ? "No previous messages in this session." : memory,
                retrievalQuestion,
                storedAnswer == null ? "No matching DB answer." : storedAnswer,
                knowledge.isBlank() ? "No retrieved context." : knowledge,
                message
        );
    }

    private String formatKnowledge(List<VectorSearchResult> retrievedKnowledge) {
        StringBuilder builder = new StringBuilder();
        int sourceNumber = 1;
        for (VectorSearchResult result : retrievedKnowledge) {
            if (builder.length() >= maxContextChars) {
                break;
            }
            builder.append("[Source ").append(sourceNumber++).append("]\n");
            builder.append("Relevance: ").append(String.format(Locale.ROOT, "%.3f", result.score())).append('\n');
            builder.append(trim(result.content(), maxChunkChars)).append("\n\n");
        }
        return trim(builder.toString(), maxContextChars);
    }

    private String trim(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
