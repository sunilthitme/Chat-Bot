package com.example.internalchatbot.service;

import java.util.Map;

public record ExtractedPage(
        int pageNumber,
        String sectionTitle,
        String text,
        Map<String, String> metadata
) {
}
