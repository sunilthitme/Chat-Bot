package com.example.internalchatbot.ai.ingestion;

import java.util.Map;

public record ExtractedPage(
        int pageNumber,
        String sectionTitle,
        String text,
        Map<String, String> metadata
) {
}
