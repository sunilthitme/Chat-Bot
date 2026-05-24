package com.example.internalchatbot.repository;

import com.example.internalchatbot.entity.EmbeddingMetadata;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmbeddingMetadataRepository extends JpaRepository<EmbeddingMetadata, Long> {

    List<EmbeddingMetadata> findByPrivateModeFalseOrderByCreatedAtDesc(Pageable pageable);

    List<EmbeddingMetadata> findBySessionIdAndPrivateModeFalseOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    List<EmbeddingMetadata> findTop300BySessionIdAndPrivateModeFalseOrderByCreatedAtDesc(String sessionId);

    List<EmbeddingMetadata> findByDocumentIdAndPrivateModeFalse(Long documentId);

    Optional<EmbeddingMetadata> findFirstByDocumentIdAndContentHashAndPrivateModeFalse(Long documentId, String contentHash);

    List<EmbeddingMetadata> findByContentHashAndPrivateModeFalse(String contentHash);
}
