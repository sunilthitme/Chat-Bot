package com.example.internalchatbot.repository;

import com.example.internalchatbot.entity.EmbeddingMetadata;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EmbeddingMetadataRepository extends JpaRepository<EmbeddingMetadata, Long> {

    List<EmbeddingMetadata> findByPrivateModeFalseOrderByCreatedAtDesc(Pageable pageable);

    List<EmbeddingMetadata> findTop300BySessionIdAndPrivateModeFalseOrderByCreatedAtDesc(String sessionId);

    List<EmbeddingMetadata> findByDocumentIdAndPrivateModeFalse(Long documentId);

    List<EmbeddingMetadata> findByContentHashAndPrivateModeFalse(String contentHash);
}
