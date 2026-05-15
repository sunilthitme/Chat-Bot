package com.example.internalchatbot.controller;

import com.example.internalchatbot.dto.IncidentAnalysisRequest;
import com.example.internalchatbot.dto.IncidentAnalysisResponse;
import com.example.internalchatbot.service.IncidentAnalysisService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
@CrossOrigin(origins = "${app.cors.allowed-origin}")
public class IncidentController {

    private final IncidentAnalysisService incidentAnalysisService;

    public IncidentController(IncidentAnalysisService incidentAnalysisService) {
        this.incidentAnalysisService = incidentAnalysisService;
    }

    @PostMapping("/analyze")
    public IncidentAnalysisResponse analyze(@Valid @RequestBody IncidentAnalysisRequest request) {
        return incidentAnalysisService.analyze(request);
    }
}
