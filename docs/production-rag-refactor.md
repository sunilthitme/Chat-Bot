# Production RAG Refactor

This refactor removes the incident-analysis module and upgrades the chatbot into a more ChatGPT-like RAG assistant.

## Removed Module

Deleted backend files:

- `controller/IncidentController.java`
- `service/IncidentAnalysisService.java`
- `dto/IncidentAnalysisRequest.java`
- `dto/IncidentAnalysisResponse.java`

Deleted frontend references:

- Incident textarea/form
- Incident API client method
- Incident-specific copy and placeholders

The `/api/incidents/analyze` route no longer exists.

## New Dependencies

```xml
org.apache.tika:tika-core:3.3.0
org.apache.tika:tika-parsers-standard-package:3.3.0
org.apache.commons:commons-csv:1.14.1
org.postgresql:postgresql
```

Existing RAG dependencies remain:

```xml
dev.langchain4j:langchain4j-ollama:1.14.1
dev.langchain4j:langchain4j-chroma:1.14.1-beta24
```

URL readability extraction also uses:

```xml
net.dankito.readability4j:readability4j:1.0.8
de.l3s.boilerpipe:boilerpipe:1.1.0
xerces:xercesImpl:2.12.2
```

## Updated Architecture

```text
controller/
  ChatController.java
  DocumentController.java
  SessionController.java
  LlmController.java
ai/
  llm/
    LlmService.java
  rag/
    AiOrchestratorService.java
  memory/
    SessionService.java
    ConversationMemoryService.java
  retrieval/
    ChatQuestionIndexService.java
    RagRetrievalService.java
  prompts/
    PromptBuilder.java
  streaming/
    ChatStreamService.java
  ingestion/
    DocumentExtractionService.java
    KnowledgeIngestionService.java
    TextChunker.java
    IngestionSummaryService.java
  crawling/
    UrlReaderService.java
    UrlValidationService.java
    ReadabilityExtractionService.java
    TrafilaturaExtractionService.java
  embeddings/
    EmbeddingService.java
  vectorstore/
    RetrievalFilter.java
    VectorStoreService.java
    ChromaEmbeddingStoreProvider.java
    ChromaHealthClient.java
entity/
  UploadedDocument.java
  EmbeddingMetadata.java
  ChatSession.java
  ChatMessage.java
repository/
  UploadedDocumentRepository.java
  EmbeddingMetadataRepository.java
  ChatQuestionTokenRepository.java
```

## RAG Flow

```text
User question
-> session memory lookup
-> bounded internal DB lookup
-> Ollama embedding generation
-> ChromaDB V2 retrieval
-> local semantic + keyword retrieval over session and long-term chunks
-> metadata boosts for code/java/spring/api/config questions
-> lexical/vector reranking to top 5 chunks
-> metadata-private prompt construction
-> streamed Ollama response
```

`AiOrchestratorService` owns the chat flow and delegates specialist work to memory, retrieval, prompt, LLM, and streaming modules. The chat path does not reread URLs, regenerate document embeddings, crawl websites, process raw HTML, or rescan full documents.

`PromptBuilder` uses strict grounding: the LLM may answer only from the internal DB answer and `[Source N]` retrieved chunks. If the answer is not present, it must return `Information not found in the indexed knowledge.`

URL ingestion is separated behind `POST /api/ingest/url`. It queues background indexing and returns immediately. The ingestion worker fetches content once, removes boilerplate, chunks text, generates embeddings once, and stores vectors for later chat retrieval.

Document upload and URL indexing now share the same ingestion state machine:

```text
UPLOAD/URL
-> EXTRACTING
-> CHUNKING
-> EMBEDDING
-> STORING
-> SUMMARIZING
-> COMPLETED / NO_EMBEDDINGS / FAILED
```

The frontend polls `GET /api/ingest/status?sessionId={sessionId}` and blocks chat input until the current session reaches a terminal state. Successful indexing returns a concise AI-generated summary before the user can ask questions.

## Document Ingestion

Supported inputs:

- PDF with page-aware PDFBox extraction
- DOCX with heading/section-aware POI extraction
- TXT and LOG with virtual page splitting
- CSV with header-aware row normalization
- Java, XML, properties, YAML, and JSON with syntax-preserving code/config extraction
- Other supported formats through Apache Tika fallback

Each chunk stores:

- source name
- source type
- source URL
- page number
- section title
- chunk index
- token estimate
- content hash
- document type
- language
- topic
- parser metadata

OCR is represented as a safe placeholder: scanned PDFs fail with a clear OCR-required message instead of silently indexing empty text.

## URL Ingestion

The URL reader now:

- accepts public HTTP and HTTPS URLs without a domain allow-list
- blocks private/local hosts by default to reduce SSRF risk
- follows redirects with browser-like request headers
- retries transient fetch failures
- extracts main article content with Readability4J
- falls back through Boilerpipe, Apache Tika HTML extraction, and structured Jsoup cleanup
- optionally falls back to the Trafilatura CLI when `url.trafilatura.enabled=true`
- reads sitemap URLs when present
- crawls same-host links up to configured depth/page limits
- removes boilerplate HTML
- deduplicates content by hash
- extracts title and URL metadata per page
- persists indexing status as `QUEUED`, `EXTRACTING`, `CHUNKING`, `EMBEDDING`, `STORING`, `SUMMARIZING`, `COMPLETED`, `NO_EMBEDDINGS`, or `FAILED`

Configuration:

```properties
url.block-private-hosts=true
url.max-pages=20
url.max-depth=2
url.timeout=15s
url.retry-attempts=3
url.retry-backoff=500ms
url.max-extracted-chars=180000
url.trafilatura.enabled=false
url.trafilatura.command=trafilatura
url.trafilatura.timeout=20s
```

## Conversation Improvements

- Session-scoped memory only
- Follow-up context is compressed into the retrieval query without making an extra LLM call
- Context window compression through `rag.max-context-chars`
- Retrieved context stays metadata-private; source URLs, vector IDs, embedding IDs, and session IDs are not included in prompts or chat responses
- Streaming tokens plus final response metadata
- No self-indexing of ordinary assistant replies, which prevents retrieval pollution
- One generation call per user question
- Ollama retry handling is limited to transient failures before streamed tokens are emitted

## Frontend Improvements

- Incident panel removed
- Streaming chat consumption
- Markdown and code block rendering
- Upload progress
- Indexing progress for extraction, chunking, embeddings, vector storage, and summary generation
- Chat input is disabled until indexing completes and the summary is visible
- Sources section removed from the UI
- Internal retrieval metadata, vector IDs, embedding IDs, chunk counts, and session UUIDs are not exposed through the chat UI
- CSV upload support
- Responsive dark mode

## Migration Steps

1. Pull the latest `specile-features` branch.
2. Restart the backend so Hibernate can add the new metadata columns.
3. If old local H2 data causes schema conflicts, stop the app and delete `backend/data`.
4. Re-upload or re-index older documents if you want the new `documentType`, `language`, `topic`, and code-aware chunk metadata on existing content.
5. Confirm Ollama models exist:

```bash
ollama pull phi3:mini
ollama pull nomic-embed-text
```

6. Confirm ChromaDB V2 heartbeat:

```bash
docker compose up -d chromadb
curl http://localhost:8000/api/v2/heartbeat
```

## Best Practices Applied

- ChromaDB client is lazy and health-checked.
- Ingestion keeps document metadata separate from generated answers and exposes only user-facing status plus summary.
- Private mode skips persistence and embedding storage.
- Retrieval has vector and keyword fallback paths.
- URL ingestion does not call the LLM, keeping indexing separate from answer generation.
- The UI no longer exposes backend credentials or private ingestion internals.
