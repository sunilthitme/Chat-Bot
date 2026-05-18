package com.example.internalchatbot.dto;

public record SourceReference(
        String sourceName,
        String sourceType,
        String sourceUrl,
        Integer pageNumber,
        String sectionTitle,
        double score,
        String preview
) {
}
