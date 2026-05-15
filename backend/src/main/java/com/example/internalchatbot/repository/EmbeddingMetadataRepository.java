package com.example.internalchatbot.repository;

import com.example.internalchatbot.entity.EmbeddingMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmbeddingMetadataRepository extends JpaRepository<EmbeddingMetadata, Long> {

    List<EmbeddingMetadata> findTop300ByPrivateModeFalseOrderByCreatedAtDesc();

    List<EmbeddingMetadata> findTop300BySessionIdAndPrivateModeFalseOrderByCreatedAtDesc(String sessionId);
}
