package com.example.internalchatbot.repository;

import com.example.internalchatbot.entity.UserMemory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserMemoryRepository extends JpaRepository<UserMemory, Long> {

    List<UserMemory> findByUserKeyOrderByImportanceScoreDescUpdatedAtDesc(String userKey, Pageable pageable);

    List<UserMemory> findBySessionIdOrderByUpdatedAtDesc(String sessionId, Pageable pageable);

    Optional<UserMemory> findFirstByUserKeyAndContentHash(String userKey, String contentHash);
}
