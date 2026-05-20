package com.example.internalchatbot.ai.ingestion;

import com.example.internalchatbot.ai.crawling.UrlReaderService;
import com.example.internalchatbot.ai.crawling.UrlValidationService;
import com.example.internalchatbot.ai.embeddings.EmbeddingService;
import com.example.internalchatbot.ai.memory.SessionService;
import com.example.internalchatbot.ai.vectorstore.VectorStoreService;

import com.example.internalchatbot.dto.DocumentUploadResponse;
import com.example.internalchatbot.dto.UrlIngestResponse;
import com.example.internalchatbot.entity.ChatSession;
import com.example.internalchatbot.entity.UploadedDocument;
import com.example.internalchatbot.repository.UploadedDocumentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);
    private static final Set<String> ACTIVE_STATUSES = Set.of("QUEUED", "INDEXING", "COMPLETED");

    private final DocumentExtractionService documentExtractionService;
    private final UrlReaderService urlReaderService;
    private final UrlValidationService urlValidationService;
    private final UploadedDocumentRepository uploadedDocumentRepository;
    private final TextChunker textChunker;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final SessionService sessionService;
    private final AsyncTaskExecutor ingestionTaskExecutor;
    private final ObjectMapper objectMapper;

    public KnowledgeIngestionService(
            DocumentExtractionService documentExtractionService,
            UrlReaderService urlReaderService,
            UrlValidationService urlValidationService,
            UploadedDocumentRepository uploadedDocumentRepository,
            TextChunker textChunker,
            EmbeddingService embeddingService,
            VectorStoreService vectorStoreService,
            SessionService sessionService,
            @Qualifier("ingestionTaskExecutor") AsyncTaskExecutor ingestionTaskExecutor
    ) {
        this.documentExtractionService = documentExtractionService;
        this.urlReaderService = urlReaderService;
        this.urlValidationService = urlValidationService;
        this.uploadedDocumentRepository = uploadedDocumentRepository;
        this.textChunker = textChunker;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.sessionService = sessionService;
        this.ingestionTaskExecutor = ingestionTaskExecutor;
        this.objectMapper = new ObjectMapper();
    }

    public DocumentUploadResponse ingestFile(
            MultipartFile file,
            String sessionId,
            String userKey,
            boolean privateMode
    ) {
        long startedAt = System.nanoTime();
        ExtractedDocument extractedDocument = documentExtractionService.extract(file);
        ChatSession session = sessionService.getOrCreateSession(sessionId, userKey, privateMode, extractedDocument.sourceName());

        if (privateMode) {
            log.info("private document parsed source={} pages={} totalMs={}", extractedDocument.sourceName(), extractedDocument.pageCount(), elapsedMillis(startedAt));
            return new DocumentUploadResponse(
                    true,
                    "SKIPPED",
                    "Private mode is enabled. Document text was parsed for this request only and was not stored."
            );
        }

        UploadedDocument document = saveDocument(session.getId(), extractedDocument, "INDEXING");
        int chunksStored = storeChunks("document", session.getId(), document.getId(), extractedDocument);
        document.setChunkCount(chunksStored);
        document.setIngestionStatus(chunksStored > 0 ? "COMPLETED" : "NO_EMBEDDINGS");
        uploadedDocumentRepository.save(document);
        log.info(
                "document ingestion completed documentId={} source={} pages={} chunks={} totalMs={}",
                document.getId(),
                extractedDocument.sourceName(),
                extractedDocument.pageCount(),
                chunksStored,
                elapsedMillis(startedAt)
        );

        return new DocumentUploadResponse(
                false,
                "COMPLETED",
                "Document indexed with " + extractedDocument.pageCount() + " parsed page(s)."
        );
    }

    public UrlIngestResponse ingestUrl(
            String url,
            String sessionId,
            String userKey,
            boolean privateMode,
            boolean loginRequired
    ) {
        URI normalizedUri = urlValidationService.validate(url);
        String normalizedUrl = normalizedUri.toString();
        ChatSession session = sessionService.getOrCreateSession(sessionId, userKey, privateMode, "Website");
        if (privateMode) {
            return new UrlIngestResponse(
                    true,
                    "SKIPPED",
                    "Private mode is enabled. The URL was not indexed or saved."
            );
        }

        return uploadedDocumentRepository
                .findFirstBySourceUrlAndSourceTypeAndPrivateModeFalseOrderByCreatedAtDesc(normalizedUrl, "url")
                .filter(document -> ACTIVE_STATUSES.contains(document.getIngestionStatus()))
                .map(document -> new UrlIngestResponse(
                        false,
                        document.getIngestionStatus(),
                        "That page is already in the indexing pipeline. Ask a question once indexing completes."
                ))
                .orElseGet(() -> queueUrlIndexing(normalizedUrl, session.getId(), loginRequired));
    }

    private UrlIngestResponse queueUrlIndexing(String normalizedUrl, String sessionId, boolean loginRequired) {
        UploadedDocument document = queuedUrlDocument(sessionId, normalizedUrl);
        UploadedDocument queued = uploadedDocumentRepository.save(document);
        ingestionTaskExecutor.execute(() -> indexQueuedUrl(queued.getId(), normalizedUrl, sessionId, loginRequired));
        return new UrlIngestResponse(
                false,
                "QUEUED",
                "I started indexing that page in the background. Ask your question after indexing finishes."
        );
    }

    private void indexQueuedUrl(Long documentId, String normalizedUrl, String sessionId, boolean loginRequired) {
        long startedAt = System.nanoTime();
        updateUrlStatus(documentId, "INDEXING", null);
        try {
            ExtractedDocument extractedDocument = urlReaderService.read(normalizedUrl, loginRequired);
            UploadedDocument document = uploadedDocumentRepository.findById(documentId)
                    .orElseThrow(() -> new IllegalStateException("Queued URL document not found"));
            applyExtractedDocument(document, extractedDocument, "INDEXING");
            uploadedDocumentRepository.save(document);

            int chunksStored = storeChunks("url", sessionId, documentId, extractedDocument);
            document.setChunkCount(chunksStored);
            document.setIngestionStatus(chunksStored > 0 ? "COMPLETED" : "NO_EMBEDDINGS");
            uploadedDocumentRepository.save(document);
            log.info(
                    "URL ingestion completed documentId={} urlHash={} pages={} chunks={} totalMs={}",
                    documentId,
                    ContentHash.sha256(normalizedUrl),
                    extractedDocument.pageCount(),
                    chunksStored,
                    elapsedMillis(startedAt)
            );
        } catch (RuntimeException ex) {
            updateUrlStatus(documentId, "FAILED", ex.getMessage());
            log.warn("URL ingestion failed documentId={} urlHash={}", documentId, ContentHash.sha256(normalizedUrl), ex);
        }
    }

    public int storeUsefulKnowledge(String sessionId, String sourceName, String content, boolean privateMode) {
        if (privateMode || content == null || content.isBlank()) {
            return 0;
        }
        ExtractedDocument document = new ExtractedDocument(
                sourceName,
                "conversation",
                null,
                "text/plain",
                "conversation-memory",
                ContentHash.sha256(content),
                Map.of("source", "assistant-response"),
                List.of(new ExtractedPage(1, "Conversation summary", content, Map.of("parser", "conversation")))
        );
        return storeChunks("conversation", sessionId, null, document);
    }

    private UploadedDocument saveDocument(String sessionId, ExtractedDocument extractedDocument, String status) {
        UploadedDocument document = new UploadedDocument();
        applyExtractedDocument(document, extractedDocument, status);
        document.setSessionId(sessionId);
        document.setPrivateMode(false);
        return uploadedDocumentRepository.save(document);
    }

    private UploadedDocument queuedUrlDocument(String sessionId, String normalizedUrl) {
        UploadedDocument document = new UploadedDocument();
        document.setSessionId(sessionId);
        document.setSourceName("Website");
        document.setSourceType("url");
        document.setSourceUrl(normalizedUrl);
        document.setMediaType("text/html");
        document.setParserName("queued-url-ingestion");
        document.setContentHash(ContentHash.sha256(normalizedUrl));
        document.setPageCount(0);
        document.setChunkCount(0);
        document.setIngestionStatus("QUEUED");
        document.setExtractedText("");
        document.setMetadataJson("{}");
        document.setPrivateMode(false);
        return document;
    }

    private void applyExtractedDocument(UploadedDocument document, ExtractedDocument extractedDocument, String status) {
        document.setSourceName(extractedDocument.sourceName());
        document.setSourceType(extractedDocument.sourceType());
        document.setSourceUrl(extractedDocument.sourceUrl());
        document.setMediaType(extractedDocument.mediaType());
        document.setParserName(extractedDocument.parserName());
        document.setContentHash(extractedDocument.contentHash());
        document.setPageCount(extractedDocument.pageCount());
        document.setChunkCount(0);
        document.setIngestionStatus(status);
        document.setExtractedText(extractedDocument.combinedText());
        document.setMetadataJson(toJson(extractedDocument.metadata()));
    }

    private void updateUrlStatus(Long documentId, String status, String reason) {
        uploadedDocumentRepository.findById(documentId).ifPresent(document -> {
            document.setIngestionStatus(status);
            if (reason != null && !reason.isBlank()) {
                document.setMetadataJson(toJson(Map.of("error", trim(reason, 500))));
            }
            uploadedDocumentRepository.save(document);
        });
    }

    private int storeChunks(
            String namespace,
            String sessionId,
            Long documentId,
            ExtractedDocument extractedDocument
    ) {
        List<DocumentChunk> chunks = textChunker.chunk(extractedDocument);
        if (chunks.isEmpty()) {
            return 0;
        }

        List<String> chunkTexts = chunks.stream()
                .map(DocumentChunk::text)
                .toList();

        List<List<Double>> vectors;
        try {
            vectors = embeddingService.embedAll(chunkTexts);
        } catch (RuntimeException ex) {
            log.warn("Batch embedding generation failed for source={}. Falling back to per-chunk embedding.", extractedDocument.sourceName(), ex);
            vectors = chunkTexts.stream()
                    .map(text -> {
                        try {
                            return embeddingService.embed(text);
                        } catch (RuntimeException innerEx) {
                            log.warn("Embedding generation failed for source={}", extractedDocument.sourceName(), innerEx);
                            return List.<Double>of();
                        }
                    })
                    .toList();
        }

        int stored = 0;
        for (int index = 0; index < chunks.size(); index++) {
            List<Double> vector = index < vectors.size() ? vectors.get(index) : List.of();
            if (vector.isEmpty()) {
                continue;
            }
            vectorStoreService.store(namespace, sessionId, documentId, chunks.get(index), vector, false);
            stored++;
        }
        return stored;
    }

    private String toJson(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize document metadata", ex);
        }
    }

    private String trim(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars) + "...";
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
