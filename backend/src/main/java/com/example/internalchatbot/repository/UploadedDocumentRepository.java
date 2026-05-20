package com.example.internalchatbot.repository;

import com.example.internalchatbot.entity.UploadedDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UploadedDocumentRepository extends JpaRepository<UploadedDocument, Long> {

    List<UploadedDocument> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    List<UploadedDocument> findByContentHashAndPrivateModeFalse(String contentHash);

    Optional<UploadedDocument> findFirstBySessionIdAndPrivateModeFalseOrderByCreatedAtDesc(String sessionId);

    boolean existsBySessionIdAndPrivateModeFalseAndIngestionStatusIn(String sessionId, Collection<String> statuses);

    Optional<UploadedDocument> findFirstBySourceUrlAndSourceTypeAndPrivateModeFalseOrderByCreatedAtDesc(
            String sourceUrl,
            String sourceType
    );
}
