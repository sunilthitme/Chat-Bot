package com.example.internalchatbot.ai.ingestion;

import com.example.internalchatbot.ai.llm.LlmService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Locale;

@Service
public class IngestionSummaryService {

    private static final Logger log = LoggerFactory.getLogger(IngestionSummaryService.class);

    private final LlmService llmService;
    private final TextNormalizer textNormalizer;
    private final int maxSummaryInputChars;

    public IngestionSummaryService(
            LlmService llmService,
            TextNormalizer textNormalizer,
            @Value("${ingestion.summary.max-input-chars:6000}") int maxSummaryInputChars
    ) {
        this.llmService = llmService;
        this.textNormalizer = textNormalizer;
        this.maxSummaryInputChars = Math.max(1500, maxSummaryInputChars);
    }

    public String summarize(ExtractedDocument document) {
        String text = sample(document.combinedText());
        if (text.isBlank()) {
            return "Content was indexed, but there was not enough readable text to generate a summary.";
        }

        if (!llmService.isEnabled()) {
            return fallbackSummary(document.sourceName(), text);
        }

        String prompt = """
                You summarize uploaded knowledge for a user before they start asking questions.

                Rules:
                - Use only the provided extracted content.
                - Do not invent facts.
                - Be concise and user-friendly.
                - Mention major topics, technologies, entities, or concepts when present.
                - Return 2 to 4 sentences.

                Content name: %s
                Content type: %s

                Extracted content:
                %s

                Summary:
                """.formatted(document.sourceName(), document.sourceType(), text);

        try {
            String summary = textNormalizer.compact(llmService.generateResponse(prompt, 0.1));
            if (!summary.isBlank()) {
                return trim(summary, 900);
            }
        } catch (RuntimeException ex) {
            log.warn("AI summary generation failed for source={}. Falling back to extractive summary.", document.sourceName(), ex);
        }
        return fallbackSummary(document.sourceName(), text);
    }

    private String fallbackSummary(String sourceName, String text) {
        String compact = textNormalizer.compact(text);
        String firstSentences = String.join(" ", Arrays.stream(compact.split("(?<=[.!?])\\s+"))
                .filter(sentence -> sentence.length() > 20)
                .limit(3)
                .toList());
        String summaryBody = firstSentences.isBlank() ? trim(compact, 450) : trim(firstSentences, 650);
        return "This content from " + sourceName + " discusses " + lowerFirst(summaryBody);
    }

    private String sample(String text) {
        String compact = textNormalizer.compact(text);
        if (compact.length() <= maxSummaryInputChars) {
            return compact;
        }
        int headChars = (int) (maxSummaryInputChars * 0.7);
        int tailChars = maxSummaryInputChars - headChars;
        return compact.substring(0, headChars)
                + "\n\n[...content trimmed for summary...]\n\n"
                + compact.substring(compact.length() - tailChars);
    }

    private String lowerFirst(String text) {
        if (text == null || text.isBlank()) {
            return "the indexed material.";
        }
        String trimmed = text.trim();
        return trimmed.substring(0, 1).toLowerCase(Locale.ROOT) + trimmed.substring(1);
    }

    private String trim(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars).trim() + "...";
    }
}
