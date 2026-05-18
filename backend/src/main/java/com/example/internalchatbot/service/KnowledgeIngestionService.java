package com.example.internalchatbot.service;

import com.example.internalchatbot.dto.DocumentUploadResponse;
import com.example.internalchatbot.dto.UrlIngestResponse;
import com.example.internalchatbot.entity.ChatSession;
import com.example.internalchatbot.entity.UploadedDocument;
import com.example.internalchatbot.repository.UploadedDocumentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

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
    private final ObjectMapper objectMapper;

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
        this.objectMapper = new ObjectMapper();
    }

    public DocumentUploadResponse ingestFile(
            MultipartFile file,
            String sessionId,
            String userKey,
            boolean privateMode
    ) {
        ExtractedDocument extractedDocument = documentExtractionService.extract(file);
        ChatSession session = sessionService.getOrCreateSession(sessionId, userKey, privateMode, extractedDocument.sourceName());

        if (privateMode) {
            return new DocumentUploadResponse(
                    null,
                    session.getId(),
                    extractedDocument.sourceName(),
                    0,
                    true,
                    "Private mode is enabled. Document text was parsed for this request only and was not stored."
            );
        }

        UploadedDocument document = saveDocument(session.getId(), extractedDocument, "INDEXING");
        int chunksStored = storeChunks("document", session.getId(), document.getId(), extractedDocument);
        document.setChunkCount(chunksStored);
        document.setIngestionStatus(chunksStored > 0 ? "COMPLETED" : "NO_EMBEDDINGS");
        uploadedDocumentRepository.save(document);

        return new DocumentUploadResponse(
                document.getId(),
                session.getId(),
                extractedDocument.sourceName(),
                chunksStored,
                false,
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
        ExtractedDocument extractedDocument = urlReaderService.read(url, loginRequired);
        ChatSession session = sessionService.getOrCreateSession(sessionId, userKey, privateMode, url);
        String summary = summarizeExtractedDocument(extractedDocument);

        if (privateMode) {
            return new UrlIngestResponse(null, session.getId(), url, 0, true, summary);
        }

        UploadedDocument document = saveDocument(session.getId(), extractedDocument, "INDEXING");
        int chunksStored = storeChunks("url", session.getId(), document.getId(), extractedDocument);
        document.setChunkCount(chunksStored);
        document.setIngestionStatus(chunksStored > 0 ? "COMPLETED" : "NO_EMBEDDINGS");
        uploadedDocumentRepository.save(document);

        return new UrlIngestResponse(document.getId(), session.getId(), url, chunksStored, false, summary);
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
        document.setSessionId(sessionId);
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
        document.setPrivateMode(false);
        return uploadedDocumentRepository.save(document);
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

    private String summarizeExtractedDocument(ExtractedDocument document) {
        if (!llmService.isEnabled()) {
            return "Content ingested from " + document.pageCount() + " page(s).";
        }
        String content = buildSummaryContent(document);
        String prompt = """
                Summarize this source for enterprise retrieval.
                Preserve important facts, entities, procedures, warnings, and decisions.
                Keep the summary concise and useful for future question answering.

                Source: %s
                Pages: %d

                Content:
                %s
                """.formatted(document.sourceName(), document.pageCount(), content);
        try {
            return llmService.generateResponse(prompt, 0.1);
        } catch (RestClientException ex) {
            return "Content ingested. Summary unavailable because Ollama is not reachable.";
        }
    }

    private String buildSummaryContent(ExtractedDocument document) {
        StringBuilder builder = new StringBuilder();
        for (ExtractedPage page : document.pages()) {
            if (builder.length() > 12_000) {
                break;
            }
            builder.append("Page ").append(page.pageNumber()).append(": ");
            if (page.sectionTitle() != null && !page.sectionTitle().isBlank()) {
                builder.append(page.sectionTitle()).append('\n');
            }
            String text = page.text();
            builder.append(text, 0, Math.min(2_000, text.length())).append("\n\n");
        }
        return builder.toString();
    }

    private String toJson(Map<String, String> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to serialize document metadata", ex);
        }
    }
}
