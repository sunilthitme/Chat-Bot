package com.example.internalchatbot.controller;

import com.example.internalchatbot.dto.ChatRequest;
import com.example.internalchatbot.dto.ChatResponse;
import com.example.internalchatbot.service.ChatService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;

// REST controller exposes chatbot APIs for the frontend.
@RestController
@RequestMapping("/api/chat")
@CrossOrigin(origins = "${app.cors.allowed-origin}")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final AsyncTaskExecutor chatTaskExecutor;
    private final Duration sseTimeout;

    public ChatController(
            ChatService chatService,
            @Qualifier("chatTaskExecutor") AsyncTaskExecutor chatTaskExecutor,
            @Value("${chat.sse-timeout:180s}") Duration sseTimeout
    ) {
        this.chatService = chatService;
        this.chatTaskExecutor = chatTaskExecutor;
        this.sseTimeout = sseTimeout;
    }

    @PostMapping("/ask")
    public Callable<ChatResponse> ask(@Valid @RequestBody ChatRequest request) {
        return () -> chatService.ask(request);
    }

    @PostMapping("/ask/stream")
    public SseEmitter askStream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(sseTimeout.toMillis());
        emitter.onTimeout(() -> {
            log.warn("chat stream timed out sessionId={}", request.getSessionId());
            emitter.complete();
        });
        emitter.onError(error -> log.warn("chat stream failed sessionId={}", request.getSessionId(), error));

        chatTaskExecutor.execute(() -> {
            try {
                ChatResponse response = chatService.stream(request, token -> sendToken(emitter, token));
                emitter.send(SseEmitter.event().name("done").data(response));
                emitter.complete();
            } catch (Exception ex) {
                log.warn("chat stream completed with error sessionId={}", request.getSessionId(), ex);
                emitter.completeWithError(ex);
            }
        });
        return emitter;
    }

    private void sendToken(SseEmitter emitter, String token) {
        try {
            emitter.send(SseEmitter.event().name("token").data(token));
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to stream token", ex);
        }
    }
}
