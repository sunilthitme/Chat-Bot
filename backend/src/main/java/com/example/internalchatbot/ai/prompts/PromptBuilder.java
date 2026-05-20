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
                You are a production-grade enterprise AI assistant with a conversational style similar to ChatGPT.
                Follow these rules:
                1. Use current session memory to understand follow-up questions.
                2. Prefer the internal DB answer when present.
                3. Use retrieved document and website knowledge only when relevant.
                4. Answer naturally; do not copy chunks verbatim unless quoting a short exact phrase is useful.
                5. If the context is insufficient, say what is missing and give the safest next step.
                6. Use retrieved context silently; do not list sources, URLs, citations, links, or internal metadata.
                7. Never leak memory across sessions.
                8. If private mode is enabled, do not mention storing or learning from this conversation.
                9. Never expose session IDs, vector IDs, embedding IDs, database IDs, or internal metadata.
                10. Keep answers concise and conversational. Prefer a direct answer first, then short details when helpful.

                Private mode: %s

                Current session memory:
                %s

                Retrieval query:
                %s

                Internal DB answer:
                %s

                Retrieved ranked context:
                %s

                User's latest message:
                %s
                """.formatted(
                privateMode ? "enabled" : "disabled",
                memory == null || memory.isBlank() ? "No previous messages in this session." : memory,
                retrievalQuestion,
                storedAnswer == null ? "No matching DB answer." : storedAnswer,
                knowledge.isBlank() ? "No relevant vector knowledge found." : knowledge,
                message
        );
    }

    private String formatKnowledge(List<VectorSearchResult> retrievedKnowledge) {
        StringBuilder builder = new StringBuilder();
        int chunkNumber = 1;
        for (VectorSearchResult result : retrievedKnowledge) {
            if (builder.length() >= maxContextChars) {
                break;
            }
            builder.append("Context chunk ").append(chunkNumber++).append('\n');
            builder.append("Relevance: ").append(String.format(Locale.ROOT, "%.3f", result.score())).append('\n');
            builder.append("Content: ").append(trim(result.content(), maxChunkChars)).append("\n\n");
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
