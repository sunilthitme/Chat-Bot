package com.example.internalchatbot.service;

import java.util.List;
import java.util.Map;

public record ExtractedDocument(
        String sourceName,
        String sourceType,
        String sourceUrl,
        String mediaType,
        String parserName,
        String contentHash,
        Map<String, String> metadata,
        List<ExtractedPage> pages
) {
    public String combinedText() {
        StringBuilder builder = new StringBuilder();
        for (ExtractedPage page : pages) {
            if (page.sectionTitle() != null && !page.sectionTitle().isBlank()) {
                builder.append(page.sectionTitle()).append('\n');
            }
            builder.append(page.text()).append("\n\n");
        }
        return builder.toString().trim();
    }

    public int pageCount() {
        return pages == null ? 0 : pages.size();
    }
}
