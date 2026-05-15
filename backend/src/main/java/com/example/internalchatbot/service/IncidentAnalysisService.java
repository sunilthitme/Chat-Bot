package com.example.internalchatbot.service;

import com.example.internalchatbot.dto.IncidentAnalysisRequest;
import com.example.internalchatbot.dto.IncidentAnalysisResponse;
import com.example.internalchatbot.entity.ChatSession;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

@Service
public class IncidentAnalysisService {

    private final SessionService sessionService;
    private final LlmService llmService;

    public IncidentAnalysisService(SessionService sessionService, LlmService llmService) {
        this.sessionService = sessionService;
        this.llmService = llmService;
    }

    public IncidentAnalysisResponse analyze(IncidentAnalysisRequest request) {
        ChatSession session = sessionService.getOrCreateSession(
                request.sessionId(),
                request.userKey(),
                request.privateMode(),
                "ServiceNow incident analysis"
        );

        String prompt = """
                You are a senior ServiceNow incident analyst.
                Analyze the incident and provide:
                - concise issue summary
                - likely root cause
                - troubleshooting steps
                - possible resolution
                - escalation notes

                Incident details:
                %s
                """.formatted(request.incidentDetails());

        String analysis;
        if (!llmService.isEnabled()) {
            analysis = "Unable to analyze incident because Ollama is disabled.";
        } else {
            try {
                analysis = llmService.generateResponse(prompt, 0.1);
            } catch (RestClientException ex) {
                analysis = "Unable to analyze incident because Ollama is not reachable.";
            }
        }

        sessionService.saveMessage(session.getId(), "user", "Analyze incident:\n" + request.incidentDetails(), request.privateMode());
        sessionService.saveMessage(session.getId(), "assistant", analysis, request.privateMode());
        return new IncidentAnalysisResponse(session.getId(), analysis);
    }
}
