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
service/
  ChatService.java
  DocumentExtractionService.java
  UrlReaderService.java
  TextChunker.java
  EmbeddingService.java
  VectorStoreService.java
  ChromaEmbeddingStoreProvider.java
  ChromaHealthClient.java
  SessionService.java
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
-> bounded local hybrid fallback retrieval
-> relevance filtering and context ranking
-> metadata-private prompt construction
-> streamed Ollama response
```

## Document Ingestion

Supported inputs:

- PDF with page-aware PDFBox extraction
- DOCX with heading/section-aware POI extraction
- TXT and LOG with virtual page splitting
- CSV with header-aware row normalization
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
- Retrieved context stays private by default; source details are included only when the user explicitly asks for sources
- Streaming tokens plus final response metadata
- No self-indexing of ordinary assistant replies, which prevents retrieval pollution
- One generation call per user question

## Frontend Improvements

- Incident panel removed
- Streaming chat consumption
- Markdown and code block rendering
- Upload progress
- Sources section removed from the UI
- Internal retrieval metadata, vector IDs, embedding IDs, URLs, and session UUIDs are not exposed through the chat UI
- CSV upload support
- Responsive dark mode

## Migration Steps

1. Pull the latest `specile-features` branch.
2. Restart the backend so Hibernate can add the new metadata columns.
3. If old local H2 data causes schema conflicts, stop the app and delete `backend/data`.
4. Confirm Ollama models exist:

```bash
ollama pull llama3.2
ollama pull nomic-embed-text
```

5. Confirm ChromaDB V2 heartbeat:

```bash
curl http://localhost:8000/api/v2/heartbeat
```

## Best Practices Applied

- ChromaDB client is lazy and health-checked.
- Ingestion keeps document metadata separate from generated answers.
- Private mode skips persistence and embedding storage.
- Retrieval has vector and keyword fallback paths.
- URL ingestion does not call the LLM, keeping indexing separate from answer generation.
- The UI no longer exposes backend credentials or private ingestion internals.
