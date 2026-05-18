package com.example.internalchatbot.repository;

import com.example.internalchatbot.entity.EmbeddingMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmbeddingMetadataRepository extends JpaRepository<EmbeddingMetadata, Long> {

    List<EmbeddingMetadata> findTop500ByPrivateModeFalseOrderByCreatedAtDesc();

    List<EmbeddingMetadata> findTop300BySessionIdAndPrivateModeFalseOrderByCreatedAtDesc(String sessionId);

    List<EmbeddingMetadata> findByDocumentIdAndPrivateModeFalse(Long documentId);

    List<EmbeddingMetadata> findByContentHashAndPrivateModeFalse(String contentHash);
}
