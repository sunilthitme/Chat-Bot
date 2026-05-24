package com.example.internalchatbot.ai.ingestion;

import com.example.internalchatbot.ai.crawling.UrlReaderService;
import com.example.internalchatbot.ai.crawling.UrlValidationService;
import com.example.internalchatbot.ai.embeddings.EmbeddingService;
import com.example.internalchatbot.ai.memory.SessionService;
import com.example.internalchatbot.ai.vectorstore.VectorStoreService;
import com.example.internalchatbot.dto.DocumentUploadResponse;
import com.example.internalchatbot.dto.IngestionStatusResponse;
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

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class KnowledgeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private static final String STATUS_QUEUED = "QUEUED";
    private static final String STATUS_EXTRACTING = "EXTRACTING";
    private static final String STATUS_CHUNKING = "CHUNKING";
    private static final String STATUS_EMBEDDING = "EMBEDDING";
    private static final String STATUS_STORING = "STORING";
    private static final String STATUS_SUMMARIZING = "SUMMARIZING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_NO_EMBEDDINGS = "NO_EMBEDDINGS";
    private static final String STATUS_FAILED = "FAILED";

    private static final Set<String> ACTIVE_INGESTION_STATUSES = Set.of(
            STATUS_QUEUED,
            STATUS_EXTRACTING,
            STATUS_CHUNKING,
            STATUS_EMBEDDING,
            STATUS_STORING,
            STATUS_SUMMARIZING,
            "INDEXING"
    );

    private static final Set<String> DUPLICATE_SKIP_STATUSES = Set.of(
            STATUS_QUEUED,
            STATUS_EXTRACTING,
            STATUS_CHUNKING,
            STATUS_EMBEDDING,
            STATUS_STORING,
            STATUS_SUMMARIZING,
            STATUS_COMPLETED,
            STATUS_NO_EMBEDDINGS,
            "INDEXING"
    );

    private final DocumentExtractionService documentExtractionService;
    private final UrlReaderService urlReaderService;
    private final UrlValidationService urlValidationService;
    private final UploadedDocumentRepository uploadedDocumentRepository;
    private final TextChunker textChunker;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final IngestionSummaryService ingestionSummaryService;
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
            IngestionSummaryService ingestionSummaryService,
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
        this.ingestionSummaryService = ingestionSummaryService;
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
        QueuedFile queuedFile = queuedFile(file);
        ChatSession session = sessionService.getOrCreateSession(sessionId, userKey, privateMode, queuedFile.filename());

        if (privateMode) {
            return new DocumentUploadResponse(
                    true,
                    "SKIPPED",
                    "Private mode is enabled. The document was not indexed or saved."
            );
        }

        UploadedDocument queuedDocument = uploadedDocumentRepository.save(queuedFileDocument(session.getId(), session.getUserKey(), queuedFile));
        ingestionTaskExecutor.execute(() -> indexQueuedFile(queuedDocument.getId(), session.getId(), session.getUserKey(), queuedFile));

        return new DocumentUploadResponse(
                false,
                STATUS_QUEUED,
                "Indexing started. I will summarize the content when indexing completes."
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
                .filter(document -> DUPLICATE_SKIP_STATUSES.contains(document.getIngestionStatus()))
                .map(document -> new UrlIngestResponse(
                        false,
                        document.getIngestionStatus(),
                        messageFor(document)
                ))
                .orElseGet(() -> queueUrlIndexing(normalizedUrl, session.getId(), session.getUserKey(), loginRequired));
    }

    public IngestionStatusResponse statusForSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return idleStatus();
        }

        return uploadedDocumentRepository
                .findFirstBySessionIdAndPrivateModeFalseOrderByCreatedAtDesc(sessionId)
                .map(document -> new IngestionStatusResponse(
                        ACTIVE_INGESTION_STATUSES.contains(document.getIngestionStatus()),
                        document.getIngestionStatus(),
                        stageFor(document.getIngestionStatus()),
                        messageFor(document),
                        publicSummary(document),
                        false
                ))
                .orElseGet(this::idleStatus);
    }

    public boolean hasActiveIngestion(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return false;
        }
        return uploadedDocumentRepository.existsBySessionIdAndPrivateModeFalseAndIngestionStatusIn(
                sessionId,
                ACTIVE_INGESTION_STATUSES
        );
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
        return storeChunks("conversation", sessionId, null, null, document);
    }

    private UrlIngestResponse queueUrlIndexing(String normalizedUrl, String sessionId, String userKey, boolean loginRequired) {
        UploadedDocument document = queuedUrlDocument(sessionId, userKey, normalizedUrl);
        UploadedDocument queued = uploadedDocumentRepository.save(document);
        ingestionTaskExecutor.execute(() -> indexQueuedUrl(queued.getId(), normalizedUrl, sessionId, userKey, loginRequired));
        return new UrlIngestResponse(
                false,
                STATUS_QUEUED,
                "Indexing started. I will summarize the page when indexing completes."
        );
    }

    private void indexQueuedFile(Long documentId, String sessionId, String userKey, QueuedFile queuedFile) {
        long startedAt = System.nanoTime();
        updateDocumentStatus(documentId, STATUS_EXTRACTING, null);
        try {
            ExtractedDocument extractedDocument = documentExtractionService.extract(
                    queuedFile.filename(),
                    queuedFile.contentType(),
                    queuedFile.bytes()
            );
            UploadedDocument document = uploadedDocumentRepository.findById(documentId)
                    .orElseThrow(() -> new IllegalStateException("Queued document not found"));
            applyExtractedDocument(document, extractedDocument, STATUS_CHUNKING);
            uploadedDocumentRepository.save(document);

            int chunksStored = storeChunks("document", sessionId, userKey, documentId, extractedDocument);
            completeIndexedDocument(document, extractedDocument, chunksStored);
            log.info(
                    "document ingestion completed documentId={} source={} pages={} chunks={} totalMs={}",
                    documentId,
                    extractedDocument.sourceName(),
                    extractedDocument.pageCount(),
                    chunksStored,
                    elapsedMillis(startedAt)
            );
        } catch (RuntimeException ex) {
            updateDocumentStatus(documentId, STATUS_FAILED, ex.getMessage());
            log.warn("document ingestion failed documentId={} sourceHash={}", documentId, ContentHash.sha256(queuedFile.filename()), ex);
        }
    }

    private void indexQueuedUrl(Long documentId, String normalizedUrl, String sessionId, String userKey, boolean loginRequired) {
        long startedAt = System.nanoTime();
        updateDocumentStatus(documentId, STATUS_EXTRACTING, null);
        try {
            ExtractedDocument extractedDocument = urlReaderService.read(normalizedUrl, loginRequired);
            UploadedDocument document = uploadedDocumentRepository.findById(documentId)
                    .orElseThrow(() -> new IllegalStateException("Queued URL document not found"));
            applyExtractedDocument(document, extractedDocument, STATUS_CHUNKING);
            uploadedDocumentRepository.save(document);

            int chunksStored = storeChunks("url", sessionId, userKey, documentId, extractedDocument);
            completeIndexedDocument(document, extractedDocument, chunksStored);
            log.info(
                    "URL ingestion completed documentId={} urlHash={} pages={} chunks={} totalMs={}",
                    documentId,
                    ContentHash.sha256(normalizedUrl),
                    extractedDocument.pageCount(),
                    chunksStored,
                    elapsedMillis(startedAt)
            );
        } catch (RuntimeException ex) {
            updateDocumentStatus(documentId, STATUS_FAILED, ex.getMessage());
            log.warn("URL ingestion failed documentId={} urlHash={}", documentId, ContentHash.sha256(normalizedUrl), ex);
        }
    }

    private void completeIndexedDocument(UploadedDocument document, ExtractedDocument extractedDocument, int chunksStored) {
        document.setChunkCount(chunksStored);
        document.setIngestionStatus(STATUS_SUMMARIZING);
        uploadedDocumentRepository.save(document);

        String summary = ingestionSummaryService.summarize(extractedDocument);
        document.setSummary(summary);
        document.setIngestionStatus(chunksStored > 0 ? STATUS_COMPLETED : STATUS_NO_EMBEDDINGS);
        uploadedDocumentRepository.save(document);
        if (chunksStored > 0) {
            sessionService.activateDocument(document.getSessionId(), document.getId(), document.getSourceName());
            log.info(
                    "active document set sessionId={} documentId={} source={} chunks={}",
                    safe(document.getSessionId()),
                    document.getId(),
                    document.getSourceName(),
                    chunksStored
            );
        }
    }

    private QueuedFile queuedFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        try {
            return new QueuedFile(
                    sanitizeFilename(file.getOriginalFilename()),
                    file.getContentType(),
                    file.getBytes()
            );
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read uploaded document", ex);
        }
    }

    private UploadedDocument queuedFileDocument(String sessionId, String userKey, QueuedFile file) {
        UploadedDocument document = new UploadedDocument();
        document.setSessionId(sessionId);
        document.setUserKey(userKey);
        document.setSourceName(file.filename());
        document.setSourceType("file");
        document.setSourceUrl(null);
        document.setMediaType(defaultIfBlank(file.contentType(), "application/octet-stream"));
        document.setParserName("queued-document-ingestion");
        document.setContentHash(ContentHash.sha256(file.bytes()));
        document.setPageCount(0);
        document.setChunkCount(0);
        document.setIngestionStatus(STATUS_QUEUED);
        document.setExtractedText("");
        document.setMetadataJson("{}");
        document.setPrivateMode(false);
        return document;
    }

    private UploadedDocument queuedUrlDocument(String sessionId, String userKey, String normalizedUrl) {
        UploadedDocument document = new UploadedDocument();
        document.setSessionId(sessionId);
        document.setUserKey(userKey);
        document.setSourceName("Website");
        document.setSourceType("url");
        document.setSourceUrl(normalizedUrl);
        document.setMediaType("text/html");
        document.setParserName("queued-url-ingestion");
        document.setContentHash(ContentHash.sha256(normalizedUrl));
        document.setPageCount(0);
        document.setChunkCount(0);
        document.setIngestionStatus(STATUS_QUEUED);
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

    private int storeChunks(
            String namespace,
            String sessionId,
            String userKey,
            Long documentId,
            ExtractedDocument extractedDocument
    ) {
        updateDocumentStatus(documentId, STATUS_CHUNKING, null);
        List<DocumentChunk> chunks = textChunker.chunk(extractedDocument);
        if (chunks.isEmpty()) {
            return 0;
        }

        List<String> chunkTexts = chunks.stream()
                .map(DocumentChunk::text)
                .toList();

        updateDocumentStatus(documentId, STATUS_EMBEDDING, null);
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

        updateDocumentStatus(documentId, STATUS_STORING, null);
        int stored = 0;
        for (int index = 0; index < chunks.size(); index++) {
            List<Double> vector = index < vectors.size() ? vectors.get(index) : List.of();
            if (vector.isEmpty()) {
                continue;
            }
            vectorStoreService.store(namespace, sessionId, userKey, documentId, chunks.get(index), vector, false);
            stored++;
        }
        return stored;
    }

    private void updateDocumentStatus(Long documentId, String status, String reason) {
        if (documentId == null) {
            return;
        }
        uploadedDocumentRepository.findById(documentId).ifPresent(document -> {
            document.setIngestionStatus(status);
            if (reason != null && !reason.isBlank()) {
                document.setMetadataJson(toJson(Map.of("error", trim(reason, 500))));
            }
            uploadedDocumentRepository.save(document);
        });
    }

    private IngestionStatusResponse idleStatus() {
        return new IngestionStatusResponse(
                false,
                "IDLE",
                "Ready",
                "No content is currently being indexed.",
                "",
                false
        );
    }

    private String stageFor(String status) {
        return switch (defaultIfBlank(status, "IDLE")) {
            case STATUS_QUEUED -> "Indexing started";
            case STATUS_EXTRACTING, "INDEXING" -> "Extracting content";
            case STATUS_CHUNKING -> "Chunking content";
            case STATUS_EMBEDDING -> "Generating embeddings";
            case STATUS_STORING -> "Storing in ChromaDB";
            case STATUS_SUMMARIZING -> "Generating summary";
            case STATUS_COMPLETED -> "Indexing completed";
            case STATUS_NO_EMBEDDINGS -> "Indexed without embeddings";
            case STATUS_FAILED -> "Indexing failed";
            default -> "Ready";
        };
    }

    private String messageFor(UploadedDocument document) {
        return switch (defaultIfBlank(document.getIngestionStatus(), "IDLE")) {
            case STATUS_QUEUED -> "Indexing started. Extracting content will begin shortly.";
            case STATUS_EXTRACTING, "INDEXING" -> "Extracting and cleaning readable content.";
            case STATUS_CHUNKING -> "Splitting content into retrieval-ready chunks.";
            case STATUS_EMBEDDING -> "Generating embeddings with nomic-embed-text.";
            case STATUS_STORING -> "Storing embeddings in the vector database.";
            case STATUS_SUMMARIZING -> "Generating a concise summary of the indexed content.";
            case STATUS_COMPLETED -> completedMessage(document);
            case STATUS_NO_EMBEDDINGS -> noEmbeddingsMessage(document);
            case STATUS_FAILED -> "Indexing failed. Please try another document or URL.";
            default -> "No content is currently being indexed.";
        };
    }

    private String completedMessage(UploadedDocument document) {
        String summary = defaultIfBlank(document.getSummary(), "");
        if (summary.isBlank()) {
            return "Content indexed successfully. You can now ask questions about this content.";
        }
        return "Content indexed successfully.\n\nSummary:\n" + summary + "\n\nYou can now ask questions about this content.";
    }

    private String noEmbeddingsMessage(UploadedDocument document) {
        String summary = defaultIfBlank(document.getSummary(), "");
        if (summary.isBlank()) {
            return "Content was extracted, but no searchable embeddings were stored.";
        }
        return "Content was extracted, but no searchable embeddings were stored.\n\nSummary:\n" + summary;
    }

    private String publicSummary(UploadedDocument document) {
        if (!STATUS_COMPLETED.equals(document.getIngestionStatus()) && !STATUS_NO_EMBEDDINGS.equals(document.getIngestionStatus())) {
            return "";
        }
        return defaultIfBlank(document.getSummary(), "");
    }

    private String toJson(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize document metadata", ex);
        }
    }

    private String sanitizeFilename(String filename) {
        return filename == null || filename.isBlank() ? "uploaded-file" : filename.replaceAll("[\\\\/]+", "_");
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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

    private String safe(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= 12 ? value : value.substring(0, 12);
    }

    private record QueuedFile(String filename, String contentType, byte[] bytes) {
    }
}
