package com.example.internalchatbot.ai.ingestion;

import org.springframework.stereotype.Component;

@Component
public class TextNormalizer {

    public String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace('\u0000', ' ')
                .replace('\u00a0', ' ')
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .replaceAll(" {2,}", " ")
                .trim();
    }

    public String compact(String text) {
        return normalize(text).replaceAll("\\s+", " ").trim();
    }
}
