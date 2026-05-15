package com.example.internalchatbot.repository;

import com.example.internalchatbot.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {

    List<ChatSession> findByUserKeyOrderByUpdatedAtDesc(String userKey);
}
