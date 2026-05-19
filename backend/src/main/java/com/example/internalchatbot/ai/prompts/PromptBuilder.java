package com.example.internalchatbot.ai.prompts;

import com.example.internalchatbot.ai.vectorstore.VectorSearchResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class PromptBuilder {

    private final int maxContextChars;

    public PromptBuilder(@Value("${rag.max-context-chars:9000}") int maxContextChars) {
        this.maxContextChars = Math.max(2_000, maxContextChars);
    }

    public String buildChatPrompt(
            String message,
            String retrievalQuestion,
            String memory,
            String storedAnswer,
            List<VectorSearchResult> retrievedKnowledge,
            boolean privateMode,
            boolean explicitSourceRequest
    ) {
        String knowledge = formatKnowledge(retrievedKnowledge, explicitSourceRequest);

        return """
                You are a production-grade enterprise AI assistant with a conversational style similar to ChatGPT.
                Follow these rules:
                1. Use current session memory to understand follow-up questions.
                2. Prefer the internal DB answer when present.
                3. Use retrieved document and website knowledge only when relevant.
                4. Answer naturally; do not copy chunks verbatim unless quoting a short exact phrase is useful.
                5. If the context is insufficient, say what is missing and give the safest next step.
                6. Use retrieved context silently unless the user explicitly asks for sources, citations, links, or references.
                7. Never leak memory across sessions.
                8. If private mode is enabled, do not mention storing or learning from this conversation.
                9. Never expose session IDs, vector IDs, embedding IDs, database IDs, or internal metadata.
                10. If sources were not requested, do not include URLs or source lists in the answer.

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

    public boolean isSourceRequest(String message) {
        String normalized = normalize(message);
        return normalized.contains("source")
                || normalized.contains("citation")
                || normalized.contains("cite")
                || normalized.contains("reference")
                || normalized.contains("link")
                || normalized.contains("url");
    }

    private String formatKnowledge(List<VectorSearchResult> retrievedKnowledge, boolean includeSourceDetails) {
        StringBuilder builder = new StringBuilder();
        int sourceNumber = 1;
        for (VectorSearchResult result : retrievedKnowledge) {
            if (builder.length() >= maxContextChars) {
                break;
            }
            builder.append("Source ").append(sourceNumber++).append('\n');
            builder.append("Name: ").append(includeSourceDetails ? publicSourceName(result) : "Retrieved knowledge").append('\n');
            builder.append("Type: ").append(result.sourceType()).append('\n');
            if (includeSourceDetails) {
                String publicUrl = publicSourceUrl(result.sourceUrl());
                if (publicUrl != null && !publicUrl.isBlank()) {
                    builder.append("URL: ").append(publicUrl).append('\n');
                }
            }
            if (result.pageNumber() != null) {
                builder.append("Page: ").append(result.pageNumber()).append('\n');
            }
            if (result.sectionTitle() != null && !result.sectionTitle().isBlank()) {
                builder.append("Section: ").append(result.sectionTitle()).append('\n');
            }
            builder.append("Relevance: ").append(String.format(Locale.ROOT, "%.3f", result.score())).append('\n');
            builder.append("Content: ").append(trim(result.content(), 1_600)).append("\n\n");
        }
        return trim(builder.toString(), maxContextChars);
    }

    private String publicSourceName(VectorSearchResult result) {
        if ("url".equalsIgnoreCase(result.sourceType()) && result.sourceUrl() != null && !result.sourceUrl().isBlank()) {
            return publicText(result.sourceUrl(), "Website");
        }
        String value = publicText(result.sourceName(), "Knowledge source");
        if (looksInternal(value)) {
            return "Knowledge source";
        }
        return value;
    }

    private String publicSourceUrl(String sourceUrl) {
        String value = publicText(sourceUrl, null);
        if (value == null || looksInternal(value)) {
            return null;
        }
        return value;
    }

    private String publicText(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value
                .replaceAll("(?i)embedding-[0-9a-f-]+", "")
                .replaceAll("(?i)session[_ -]?[0-9a-f-]{8,}", "")
                .replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private boolean looksInternal(String value) {
        return value == null
                || value.isBlank()
                || value.matches(".*[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}.*")
                || value.matches("(?i).*\\b(embedding|vector|session)[_-]?[0-9a-f-]{6,}.*");
    }

    private String normalize(String text) {
        return text == null
                ? ""
                : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }

    private String trim(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars) + "...";
    }
}
