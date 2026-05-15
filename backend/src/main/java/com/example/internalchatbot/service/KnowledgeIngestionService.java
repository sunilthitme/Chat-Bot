package com.example.internalchatbot.service;

import com.example.internalchatbot.dto.DocumentUploadResponse;
import com.example.internalchatbot.dto.UrlIngestResponse;
import com.example.internalchatbot.entity.ChatSession;
import com.example.internalchatbot.entity.UploadedDocument;
import com.example.internalchatbot.repository.UploadedDocumentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private final DocumentExtractionService documentExtractionService;
    private final UrlReaderService urlReaderService;
    private final UploadedDocumentRepository uploadedDocumentRepository;
    private final TextChunker textChunker;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final SessionService sessionService;
    private final LlmService llmService;

    public KnowledgeIngestionService(
            DocumentExtractionService documentExtractionService,
            UrlReaderService urlReaderService,
            UploadedDocumentRepository uploadedDocumentRepository,
            TextChunker textChunker,
            EmbeddingService embeddingService,
            VectorStoreService vectorStoreService,
            SessionService sessionService,
            LlmService llmService
    ) {
        this.documentExtractionService = documentExtractionService;
        this.urlReaderService = urlReaderService;
        this.uploadedDocumentRepository = uploadedDocumentRepository;
        this.textChunker = textChunker;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.sessionService = sessionService;
        this.llmService = llmService;
    }

    public DocumentUploadResponse ingestFile(
            MultipartFile file,
            String sessionId,
            String userKey,
            boolean privateMode
    ) {
        String extractedText = documentExtractionService.extractText(file);
        ChatSession session = sessionService.getOrCreateSession(sessionId, userKey, privateMode, file.getOriginalFilename());
        String sourceName = file.getOriginalFilename() == null ? "uploaded-file" : file.getOriginalFilename();

        if (privateMode) {
            return new DocumentUploadResponse(
                    null,
                    session.getId(),
                    sourceName,
                    0,
                    true,
                    "Private mode is enabled. Text was read for this request only and was not stored."
            );
        }

        UploadedDocument document = saveDocument(session.getId(), sourceName, "file", null, extractedText, false);
        int chunksStored = storeChunks("document", session.getId(), document.getId(), sourceName, "file", extractedText);

        return new DocumentUploadResponse(
                document.getId(),
                session.getId(),
                sourceName,
                chunksStored,
                false,
                "Document indexed for RAG retrieval."
        );
    }

    public UrlIngestResponse ingestUrl(
            String url,
            String sessionId,
            String userKey,
            boolean privateMode,
            boolean loginRequired
    ) {
        String extractedText = urlReaderService.read(url, loginRequired);
        ChatSession session = sessionService.getOrCreateSession(sessionId, userKey, privateMode, url);
        String summary = summarizeUrl(extractedText);

        if (privateMode) {
            return new UrlIngestResponse(null, session.getId(), url, 0, true, summary);
        }

        UploadedDocument document = saveDocument(session.getId(), url, "url", url, extractedText, false);
        int chunksStored = storeChunks("url", session.getId(), document.getId(), url, "url", extractedText);
        return new UrlIngestResponse(document.getId(), session.getId(), url, chunksStored, false, summary);
    }

    public int storeUsefulKnowledge(String sessionId, String sourceName, String content, boolean privateMode) {
        if (privateMode || content == null || content.isBlank()) {
            return 0;
        }
        return storeChunks("conversation", sessionId, null, sourceName, "conversation", content);
    }

    private UploadedDocument saveDocument(
            String sessionId,
            String sourceName,
            String sourceType,
            String sourceUrl,
            String extractedText,
            boolean privateMode
    ) {
        UploadedDocument document = new UploadedDocument();
        document.setSessionId(sessionId);
        document.setSourceName(sourceName);
        document.setSourceType(sourceType);
        document.setSourceUrl(sourceUrl);
        document.setExtractedText(extractedText);
        document.setPrivateMode(privateMode);
        return uploadedDocumentRepository.save(document);
    }

    private int storeChunks(
            String namespace,
            String sessionId,
            Long documentId,
            String sourceName,
            String sourceType,
            String extractedText
    ) {
        int stored = 0;
        for (String chunk : textChunker.chunk(extractedText)) {
            try {
                List<Double> vector = embeddingService.embed(chunk);
                if (!vector.isEmpty()) {
                    vectorStoreService.store(namespace, sessionId, documentId, sourceName, sourceType, chunk, vector, false);
                    stored++;
                }
            } catch (RuntimeException ex) {
                log.warn("Embedding generation failed for source={}", sourceName, ex);
            }
        }
        return stored;
    }

    private String summarizeUrl(String extractedText) {
        if (!llmService.isEnabled()) {
            return "URL content ingested.";
        }
        String prompt = """
                Summarize the following URL content for future enterprise knowledge retrieval.
                Keep it concise and factual.

                Content:
                %s
                """.formatted(extractedText.length() > 8000 ? extractedText.substring(0, 8000) : extractedText);
        try {
            return llmService.generateResponse(prompt, 0.1);
        } catch (RestClientException ex) {
            return "URL content ingested. Summary unavailable because Ollama is not reachable.";
        }
    }
}
