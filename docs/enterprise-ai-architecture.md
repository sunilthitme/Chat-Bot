# Enterprise AI Chatbot Architecture

This branch adds a DB-first enterprise chatbot with RAG, Ollama, multi-session memory, private mode, document ingestion, URL crawling, authenticated URL capture, and ChromaDB-backed knowledge retrieval.

## Runtime Flow

1. The frontend sends a message with `sessionId`, `userKey`, and `privateMode`.
2. `AiOrchestratorService` creates or loads the current session.
3. The internal `chat_questions` table is searched first.
4. If private mode is off, the prompt includes current-session memory and vector knowledge retrieved from persisted embeddings.
5. `LlmService` calls Ollama at `/api/generate` with a low temperature for enterprise consistency.
6. The assistant response is saved only when private mode is off.
7. Ingestion runs once per uploaded URL/document; chat requests only do bounded DB search, top-3 vector retrieval, prompt construction, and one logical Ollama generation.

## Folder Structure

```text
backend/src/main/java/com/example/internalchatbot/
  config/
    SecurityConfiguration.java
  controller/
    ChatController.java
    DocumentController.java
    LlmController.java
    SessionController.java
    ApiExceptionHandler.java
  dto/
    ChatRequest.java
    ChatResponse.java
    ChatSessionResponse.java
    CreateSessionRequest.java
    DocumentUploadResponse.java
    PrivateModeRequest.java
    StoredMessageResponse.java
    UrlIngestRequest.java
    UrlIngestResponse.java
  entity/
    AppUser.java
    ChatMessage.java
    ChatQuestion.java
    ChatSession.java
    EmbeddingMetadata.java
    UploadedDocument.java
  repository/
    AppUserRepository.java
    ChatMessageRepository.java
    ChatQuestionRepository.java
    ChatSessionRepository.java
    EmbeddingMetadataRepository.java
    UploadedDocumentRepository.java
  ai/
    llm/
      LlmService.java
    rag/
      AiOrchestratorService.java
    embeddings/
      EmbeddingService.java
    memory/
      SessionService.java
      ConversationMemoryService.java
    vectorstore/
      ChromaEmbeddingStoreProvider.java
      ChromaHealthClient.java
      VectorSearchResult.java
      VectorStoreService.java
    ingestion/
      DocumentExtractionService.java
      KnowledgeIngestionService.java
      TextChunker.java
      TextNormalizer.java
      DocumentChunk.java
      ExtractedDocument.java
      ExtractedPage.java
    crawling/
      AuthenticatedUrlReaderService.java
      ReadabilityExtractionService.java
      TrafilaturaExtractionService.java
      UrlReaderService.java
      UrlValidationService.java
    prompts/
      PromptBuilder.java
    retrieval/
      ChatQuestionIndexService.java
      RagRetrievalService.java
    streaming/
      ChatStreamService.java
```

## Backend Modules

`ChatController` exposes standard and streaming chat endpoints. It delegates to `AiOrchestratorService` for the DB-first prompt flow and to `ChatStreamService` for SSE token streaming.

`AiOrchestratorService` coordinates session state, bounded internal DB lookup, memory loading, RAG retrieval, prompt building, and one logical LLM call per user message.

`ConversationMemoryService` keeps current-session memory isolated. It compresses recent messages into the retrieval query without making another LLM call.

`RagRetrievalService` performs top-k retrieval only. It embeds the current question once, queries ChromaDB first, falls back to local vectors when needed, filters by score, and limits results to `rag.top-k`.

`PromptBuilder` owns ChatGPT-style prompt rules and strips internal identifiers from optional source context before it reaches the model.

`SessionController` supports new chats, session listing, message history, and private-mode updates. Session memory is read only from `chat_messages` for the active session.

`DocumentController` uploads PDF, DOCX, TXT, and LOG files, then delegates extraction and indexing to `KnowledgeIngestionService`.

`UrlReaderService` validates public HTTP/HTTPS URLs before reading with Jsoup. Readability4J, Boilerpipe, Apache Tika, structured Jsoup cleanup, and optional Trafilatura fallback extract readable content. `AuthenticatedUrlReaderService` uses Selenium only when backend properties enable login-required capture. Passwords remain backend-only in `application.properties`.

`EmbeddingService` uses LangChain4j `OllamaEmbeddingModel` with `nomic-embed-text`, batch generation, and an in-memory hash cache. `VectorStoreService` saves local vector metadata and syncs chunks to ChromaDB API V2 when `chroma.enabled=true`. Chroma initialization is lazy and health-checked so Spring Boot startup does not fail when Chroma is unavailable.

## Database Schema

Reference SQL is generated in:

```text
backend/src/main/resources/schema-enterprise.sql
```

It includes:

- `users`
- `chat_sessions`
- `chat_messages`
- `uploaded_documents`
- `embeddings_metadata`
- `chat_question_tokens`

Local development uses file-backed H2 at `backend/data/chatbotdb`, so uploaded knowledge survives application restarts. Production should use MySQL or PostgreSQL and managed ChromaDB storage.

Indexes are defined for session history, internal question lookup, token ranking, embedding metadata, document IDs, and content hashes to avoid repeated full table scans.

## API Surface

```http
POST /api/chat/ask
POST /api/chat/ask/stream
POST /api/ingest/url
POST /api/sessions
GET  /api/sessions
GET  /api/sessions/{sessionId}/messages
PATCH /api/sessions/{sessionId}/private-mode
POST /api/knowledge/documents
POST /api/llm/generate
```

`POST /api/ingest/url` is the dedicated URL pre-indexing API. It validates a public HTTP/HTTPS URL, queues background indexing, fetches and cleans the page, chunks extracted text, generates embeddings once, and stores vectors. Chat APIs never crawl URLs or process raw HTML.

## Private Mode Rules

When `privateMode=true`:

- User and assistant messages are not saved.
- Embeddings are not generated for long-term storage.
- Uploaded document text is not stored.
- URL content is not stored.
- Useful response knowledge is not saved for future retrieval.

## Security Guardrails

- Frontend never receives login credentials.
- URL ingestion accepts public HTTP and HTTPS URLs without a domain allow-list.
- Private/local hosts are blocked by default through `url.block-private-hosts=true`.
- Readability4J, Boilerpipe, Apache Tika, Jsoup cleanup, and optional Trafilatura CLI fallback extract human-readable content.
- Upload size is capped by `security.max-upload-bytes`.
- Supported file extensions are limited to PDF, DOCX, TXT, and LOG.
- Selenium login runs only when `secure-url.login-enabled=true`.
- No feature executes user-provided code.

## Frontend Modules

The Angular app now has a ChatGPT-style layout with:

- Session sidebar and new chat button.
- Current-session message history.
- Private mode toggle.
- File upload action.
- URL ingestion form.
- Typing/loading animation.
- No visible sources panel; internal retrieval metadata stays backend-only and is not included in chat prompts or responses.

## Configuration

```properties
ollama.enabled=true
ollama.base-url=http://localhost:11434
ollama.model=phi3:mini
ollama.embedding-model=nomic-embed-text
ollama.temperature=0.2
ollama.num-predict=384
ollama.num-ctx=4096
ollama.retry-attempts=2
ollama.retry-backoff=500ms
rag.top-k=3
rag.candidate-top-k=10
rag.chunk-size=500
rag.chunk-overlap=100
rag.max-context-chars=3000
chat.memory.max-chars=1800
chat.memory.retrieval-query-chars=600
chroma.enabled=true
chroma.base-url=http://localhost:8000
chroma.tenant-name=default
chroma.database-name=default
chroma.timeout=10s
chroma.health-check-timeout=2s
chroma.health-check-retries=3
chroma.retry-delay=30s
url.block-private-hosts=true
url.max-pages=20
url.retry-attempts=3
url.trafilatura.enabled=false
secure-url.login-enabled=false
```

For PostgreSQL:

```properties
spring.datasource.url=jdbc:postgresql://host:5432/chatbot
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
spring.jpa.hibernate.ddl-auto=validate
```

Run the reference schema in `backend/src/main/resources/schema-enterprise.sql` before using `ddl-auto=validate`.

Start local ChromaDB with:

```bash
docker compose up -d chromadb
```

## Production Recommendations

- Replace local H2 with PostgreSQL or MySQL.
- Run ChromaDB with durable volumes and backups.
- Move credentials to environment variables or a secret manager.
- Add Spring Security with JWT or SSO before multi-user rollout.
- Add audit logs that redact prompts, credentials, and private sessions.
- Add rate limiting for chat, URL ingestion, upload, and Selenium endpoints.
- Add async ingestion for large documents and URLs.
- Add embedding refresh jobs when source documents change.
- Add observability for LLM latency, embedding failures, vector hit rate, and fallback usage.
