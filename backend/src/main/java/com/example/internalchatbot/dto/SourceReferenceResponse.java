package com.example.internalchatbot.dto;

public record SourceReferenceResponse(
        String title,
        String documentType,
        Integer pageNumber,
        String sectionTitle,
        double relevance
) {
}
