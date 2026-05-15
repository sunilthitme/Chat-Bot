package com.example.internalchatbot.controller;

import com.example.internalchatbot.dto.DocumentUploadResponse;
import com.example.internalchatbot.dto.UrlIngestRequest;
import com.example.internalchatbot.dto.UrlIngestResponse;
import com.example.internalchatbot.service.KnowledgeIngestionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin(origins = "${app.cors.allowed-origin}")
public class DocumentController {

    private final KnowledgeIngestionService knowledgeIngestionService;

    public DocumentController(KnowledgeIngestionService knowledgeIngestionService) {
        this.knowledgeIngestionService = knowledgeIngestionService;
    }

    @PostMapping("/documents")
    public DocumentUploadResponse upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "local-user") String userKey,
            @RequestParam(defaultValue = "false") boolean privateMode
    ) {
        return knowledgeIngestionService.ingestFile(file, sessionId, userKey, privateMode);
    }

    @PostMapping("/urls")
    public UrlIngestResponse ingestUrl(@Valid @org.springframework.web.bind.annotation.RequestBody UrlIngestRequest request) {
        return knowledgeIngestionService.ingestUrl(
                request.url(),
                request.sessionId(),
                request.userKey(),
                request.privateMode(),
                request.loginRequired()
        );
    }
}
