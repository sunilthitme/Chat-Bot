package com.example.internalchatbot.dto;

public record SourceReference(
        String sourceName,
        String sourceType,
        double score,
        String preview
) {
}
