package com.example.internalchatbot.controller;

import com.example.internalchatbot.ai.rag.AiOrchestratorService;
import com.example.internalchatbot.ai.streaming.ChatStreamService;
import com.example.internalchatbot.dto.ChatRequest;
import com.example.internalchatbot.dto.ChatResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.Callable;

// REST controller exposes chatbot APIs for the frontend.
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "${app.cors.allowed-origin}")
public class ChatController {

    private final AiOrchestratorService aiOrchestratorService;
    private final ChatStreamService chatStreamService;

    public ChatController(
            AiOrchestratorService aiOrchestratorService,
            ChatStreamService chatStreamService
    ) {
        this.aiOrchestratorService = aiOrchestratorService;
        this.chatStreamService = chatStreamService;
    }

    @PostMapping("/ask")
    public Callable<ChatResponse> ask(@Valid @RequestBody ChatRequest request) {
        return () -> aiOrchestratorService.ask(request);
    }

    @PostMapping("/ask/stream")
    public SseEmitter askStream(@Valid @RequestBody ChatRequest request) {
        return chatStreamService.stream(request);
    }
}
