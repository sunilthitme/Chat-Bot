package com.example.internalchatbot.controller;

import com.example.internalchatbot.dto.ChatSessionResponse;
import com.example.internalchatbot.dto.CreateSessionRequest;
import com.example.internalchatbot.dto.PrivateModeRequest;
import com.example.internalchatbot.dto.StoredMessageResponse;
import com.example.internalchatbot.service.SessionService;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sessions")
@CrossOrigin(origins = "${app.cors.allowed-origin}")
public class SessionController {

    private final SessionService sessionService;

    public SessionController(SessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    public ChatSessionResponse create(@RequestBody CreateSessionRequest request) {
        return sessionService.toResponse(sessionService.createSession(request));
    }

    @GetMapping
    public List<ChatSessionResponse> list(@RequestParam(defaultValue = "local-user") String userKey) {
        return sessionService.listSessions(userKey);
    }

    @GetMapping("/{sessionId}/messages")
    public List<StoredMessageResponse> messages(@PathVariable String sessionId) {
        return sessionService.listMessages(sessionId);
    }

    @PatchMapping("/{sessionId}/private-mode")
    public ChatSessionResponse privateMode(
            @PathVariable String sessionId,
            @RequestBody PrivateModeRequest request
    ) {
        return sessionService.setPrivateMode(sessionId, request.privateMode());
    }
}
