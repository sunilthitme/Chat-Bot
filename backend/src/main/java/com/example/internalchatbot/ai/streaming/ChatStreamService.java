package com.example.internalchatbot.ai.streaming;

import com.example.internalchatbot.ai.rag.AiOrchestratorService;
import com.example.internalchatbot.dto.ChatRequest;
import com.example.internalchatbot.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;

@Service
public class ChatStreamService {

    private static final Logger log = LoggerFactory.getLogger(ChatStreamService.class);

    private final AiOrchestratorService aiOrchestratorService;
    private final AsyncTaskExecutor chatTaskExecutor;
    private final Duration sseTimeout;

    public ChatStreamService(
            AiOrchestratorService aiOrchestratorService,
            @Qualifier("chatTaskExecutor") AsyncTaskExecutor chatTaskExecutor,
            @Value("${chat.sse-timeout:180s}") Duration sseTimeout
    ) {
        this.aiOrchestratorService = aiOrchestratorService;
        this.chatTaskExecutor = chatTaskExecutor;
        this.sseTimeout = sseTimeout;
    }

    public SseEmitter stream(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(sseTimeout.toMillis());
        emitter.onTimeout(() -> {
            log.warn("chat stream timed out");
            emitter.complete();
        });
        emitter.onError(error -> log.warn("chat stream failed", error));

        chatTaskExecutor.execute(() -> {
            try {
                ChatResponse response = aiOrchestratorService.stream(request, token -> sendToken(emitter, token));
                emitter.send(SseEmitter.event().name("done").data(response));
                emitter.complete();
            } catch (Exception ex) {
                log.warn("chat stream completed with error", ex);
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
