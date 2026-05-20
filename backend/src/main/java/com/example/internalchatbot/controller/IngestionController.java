package com.example.internalchatbot.controller;

import com.example.internalchatbot.ai.ingestion.KnowledgeIngestionService;
import com.example.internalchatbot.dto.UrlIngestRequest;
import com.example.internalchatbot.dto.UrlIngestResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingest")
@CrossOrigin(origins = "${app.cors.allowed-origin}")
public class IngestionController {

    private final KnowledgeIngestionService knowledgeIngestionService;

    public IngestionController(KnowledgeIngestionService knowledgeIngestionService) {
        this.knowledgeIngestionService = knowledgeIngestionService;
    }

    @PostMapping("/url")
    public UrlIngestResponse ingestUrl(@Valid @RequestBody UrlIngestRequest request) {
        return knowledgeIngestionService.ingestUrl(
                request.url(),
                request.sessionId(),
                request.userKey(),
                request.privateMode(),
                request.loginRequired()
        );
    }
}
