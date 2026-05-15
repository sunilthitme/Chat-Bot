package com.example.internalchatbot.repository;

import com.example.internalchatbot.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findTop12BySessionIdOrderByCreatedAtDesc(String sessionId);

    List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId);
}
