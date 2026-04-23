package com.example.internalchatbot.controller;

import com.example.internalchatbot.dto.ChatRequest;
import com.example.internalchatbot.dto.ChatResponse;
import com.example.internalchatbot.service.AuthService;
import com.example.internalchatbot.service.ChatService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// REST controller exposes chatbot APIs for the frontend.
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "http://localhost:4200")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final AuthService authService;

    public ChatController(ChatService chatService, AuthService authService) {
        this.chatService = chatService;
        this.authService = authService;
    }

    @PostMapping("/ask")
    public ChatResponse ask(
            @RequestHeader("X-User-Token") String token,
            @Valid @RequestBody ChatRequest request
    ) {
        log.info("POST /api/chat/ask started");
        authService.requireAuthenticated(token);
        String reply = chatService.ask(request.getMessage());
        log.info("POST /api/chat/ask completed");
        return new ChatResponse(reply);
    }
}
