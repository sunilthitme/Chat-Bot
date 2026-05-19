package com.example.internalchatbot.repository;

import com.example.internalchatbot.entity.UploadedDocument;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UploadedDocumentRepository extends JpaRepository<UploadedDocument, Long> {

    List<UploadedDocument> findBySessionIdOrderByCreatedAtDesc(String sessionId, Pageable pageable);

    List<UploadedDocument> findByContentHashAndPrivateModeFalse(String contentHash);
}
