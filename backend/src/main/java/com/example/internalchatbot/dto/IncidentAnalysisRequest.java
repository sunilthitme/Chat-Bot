package com.example.internalchatbot.dto;

import jakarta.validation.constraints.NotBlank;

public record IncidentAnalysisRequest(
        @NotBlank(message = "Incident details are required")
        String incidentDetails,
        String sessionId,
        String userKey,
        boolean privateMode
) {
}
