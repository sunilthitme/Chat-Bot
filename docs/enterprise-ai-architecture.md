# Enterprise AI Chatbot Architecture

This branch adds a DB-first enterprise chatbot with RAG, Ollama, multi-session memory, private mode, document ingestion, URL ingestion, authenticated URL capture, and ServiceNow incident analysis.

## Runtime Flow

1. The frontend sends a message with `sessionId`, `userKey`, and `privateMode`.
2. `ChatService` creates or loads the current session.
3. The internal `chat_questions` table is searched first.
4. If private mode is off, the prompt includes current-session memory and vector knowledge retrieved from persisted embeddings.
5. `LlmService` calls Ollama at `/api/generate` with a low temperature for enterprise consistency.
6. The assistant response is saved only when private mode is off.
7. Useful assistant knowledge is chunked, embedded with LangChain4j `OllamaEmbeddingModel`, saved in local metadata, and synced to ChromaDB when available.

## Folder Structure

```text
backend/src/main/java/com/example/internalchatbot/
  config/
    SecurityConfiguration.java
  controller/
    ChatController.java
    DocumentController.java
    IncidentController.java
    LlmController.java
    SessionController.java
    ApiExceptionHandler.java
  dto/
    ChatRequest.java
    ChatResponse.java
    ChatSessionResponse.java
    CreateSessionRequest.java
    DocumentUploadResponse.java
    IncidentAnalysisRequest.java
    IncidentAnalysisResponse.java
    PrivateModeRequest.java
    SourceReference.java
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
  service/
    ChatService.java
    DocumentExtractionService.java
    EmbeddingService.java
    IncidentAnalysisService.java
    KnowledgeIngestionService.java
    LlmService.java
    SessionService.java
    TextChunker.java
    UrlReaderService.java
    UrlValidationService.java
    AuthenticatedUrlReaderService.java
    VectorStoreService.java
```

## Backend Modules

`ChatController` exposes standard and streaming chat endpoints. `ChatService` owns the DB-first prompt flow, current-session memory, RAG retrieval, and private-mode persistence decisions.

`SessionController` supports new chats, session listing, message history, and private-mode updates. Session memory is read only from `chat_messages` for the active session.

`DocumentController` uploads PDF, DOCX, TXT, and LOG files, then delegates extraction and indexing to `KnowledgeIngestionService`.

`UrlReaderService` validates URL schemes and domains before reading with Jsoup. `AuthenticatedUrlReaderService` uses Selenium only when backend properties enable login-required capture. Passwords remain backend-only in `application.properties`.

`IncidentController` delegates ServiceNow analysis to `IncidentAnalysisService`, which prompts Ollama for root cause, troubleshooting steps, and likely resolution.

`EmbeddingService` uses LangChain4j `OllamaEmbeddingModel` with `nomic-embed-text`. `VectorStoreService` saves local vector metadata and syncs chunks to ChromaDB when `chroma.enabled=true`.

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

Local development uses file-backed H2 at `backend/data/chatbotdb`, so uploaded knowledge survives application restarts. Production should use MySQL or PostgreSQL and managed ChromaDB storage.

## API Surface

```http
POST /api/chat/ask
POST /api/chat/ask/stream
POST /api/sessions
GET  /api/sessions
GET  /api/sessions/{sessionId}/messages
PATCH /api/sessions/{sessionId}/private-mode
POST /api/knowledge/documents
POST /api/knowledge/urls
POST /api/incidents/analyze
POST /api/llm/generate
```

## Private Mode Rules

When `privateMode=true`:

- User and assistant messages are not saved.
- Embeddings are not generated for long-term storage.
- Uploaded document text is not stored.
- URL content is not stored.
- Useful response knowledge is not saved for future retrieval.

## Security Guardrails

- Frontend never receives login credentials.
- URL ingestion accepts only HTTP and HTTPS.
- URL hosts must match `security.allowed-url-domains`.
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
- ServiceNow incident analysis panel.
- Typing/loading animation.
- Source display for RAG results.

## Configuration

```properties
ollama.enabled=true
ollama.base-url=http://localhost:11434
ollama.model=llama3.2
ollama.embedding-model=nomic-embed-text
ollama.temperature=0.2
rag.top-k=5
chroma.enabled=true
chroma.base-url=http://localhost:8000
security.allowed-url-domains=localhost,127.0.0.1,example.com,servicenow.com
secure-url.login-enabled=false
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
