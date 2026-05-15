package com.example.internalchatbot.service;

import com.example.internalchatbot.dto.ChatSessionResponse;
import com.example.internalchatbot.dto.CreateSessionRequest;
import com.example.internalchatbot.dto.StoredMessageResponse;
import com.example.internalchatbot.entity.AppUser;
import com.example.internalchatbot.entity.ChatMessage;
import com.example.internalchatbot.entity.ChatSession;
import com.example.internalchatbot.repository.AppUserRepository;
import com.example.internalchatbot.repository.ChatMessageRepository;
import com.example.internalchatbot.repository.ChatSessionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
public class SessionService {

    private static final String DEFAULT_USER = "local-user";

    private final AppUserRepository appUserRepository;
    private final ChatSessionRepository chatSessionRepository;
    private final ChatMessageRepository chatMessageRepository;

    public SessionService(
            AppUserRepository appUserRepository,
            ChatSessionRepository chatSessionRepository,
            ChatMessageRepository chatMessageRepository
    ) {
        this.appUserRepository = appUserRepository;
        this.chatSessionRepository = chatSessionRepository;
        this.chatMessageRepository = chatMessageRepository;
    }

    public ChatSession createSession(CreateSessionRequest request) {
        String userKey = normalizeUserKey(request == null ? null : request.userKey());
        ensureUser(userKey);

        ChatSession session = new ChatSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserKey(userKey);
        session.setTitle(resolveTitle(request == null ? null : request.title()));
        session.setPrivateMode(request != null && request.privateMode());
        return chatSessionRepository.save(session);
    }

    public ChatSession getOrCreateSession(String sessionId, String userKey, boolean privateMode, String firstMessage) {
        if (sessionId != null && !sessionId.isBlank()) {
            return chatSessionRepository.findById(sessionId)
                    .map(session -> updatePrivateMode(session, privateMode))
                    .orElseGet(() -> createSession(new CreateSessionRequest(titleFromMessage(firstMessage), userKey, privateMode)));
        }
        return createSession(new CreateSessionRequest(titleFromMessage(firstMessage), userKey, privateMode));
    }

    public List<ChatSessionResponse> listSessions(String userKey) {
        return chatSessionRepository.findByUserKeyOrderByUpdatedAtDesc(normalizeUserKey(userKey))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public ChatSessionResponse setPrivateMode(String sessionId, boolean privateMode) {
        ChatSession session = chatSessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Chat session not found"));
        session.setPrivateMode(privateMode);
        return toResponse(chatSessionRepository.save(session));
    }

    public List<StoredMessageResponse> listMessages(String sessionId) {
        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId)
                .stream()
                .map(message -> new StoredMessageResponse(
                        message.getId(),
                        message.getRole(),
                        message.getContent(),
                        message.getCreatedAt()
                ))
                .toList();
    }

    public void saveMessage(String sessionId, String role, String content, boolean privateMode) {
        if (privateMode) {
            return;
        }

        ChatMessage message = new ChatMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        chatMessageRepository.save(message);

        chatSessionRepository.findById(sessionId).ifPresent(session -> chatSessionRepository.save(session));
    }

    public String memoryForSession(String sessionId) {
        List<ChatMessage> messages = chatMessageRepository.findTop12BySessionIdOrderByCreatedAtDesc(sessionId);
        StringBuilder memory = new StringBuilder();
        for (int index = messages.size() - 1; index >= 0; index--) {
            ChatMessage message = messages.get(index);
            memory.append(message.getRole()).append(": ").append(message.getContent()).append("\n");
        }
        return memory.toString();
    }

    public ChatSessionResponse toResponse(ChatSession session) {
        return new ChatSessionResponse(
                session.getId(),
                session.getTitle(),
                session.isPrivateMode(),
                session.getCreatedAt(),
                session.getUpdatedAt()
        );
    }

    private ChatSession updatePrivateMode(ChatSession session, boolean privateMode) {
        if (session.isPrivateMode() != privateMode) {
            session.setPrivateMode(privateMode);
            return chatSessionRepository.save(session);
        }
        return session;
    }

    private void ensureUser(String username) {
        appUserRepository.findByUsernameIgnoreCase(username).orElseGet(() -> {
            AppUser appUser = new AppUser();
            appUser.setUsername(username);
            return appUserRepository.save(appUser);
        });
    }

    private String normalizeUserKey(String userKey) {
        return userKey == null || userKey.isBlank() ? DEFAULT_USER : userKey.trim().toLowerCase();
    }

    private String resolveTitle(String title) {
        return title == null || title.isBlank() ? "New chat" : title.trim();
    }

    private String titleFromMessage(String message) {
        if (message == null || message.isBlank()) {
            return "New chat";
        }
        String trimmed = message.trim();
        return trimmed.length() <= 60 ? trimmed : trimmed.substring(0, 57) + "...";
    }
}
