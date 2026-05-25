# Internal Chatbot Backend

Spring Boot REST API for a DB-first enterprise chatbot with local RAG storage: H2 JSON vector storage, Apache Lucene BM25 keyword search, Java cosine similarity, session memory, async ingestion, GitHub Models chat generation, and confidence-aware prompts.

## Run

```bash
mvn spring-boot:run
```

## Required AI Providers

```bash
ollama pull nomic-embed-text
```

Set `GITHUB_TOKEN` with a GitHub token that has GitHub Models access. Ollama remains local-only for the existing embedding pipeline.

No database server, vector database, Docker service, or Python service is required.

## Retrieval Architecture

- `EmbeddingService` embeds document chunks and user queries with `nomic-embed-text`.
- `VectorStoreService` stores chunk metadata and vector JSON in H2.
- `LuceneIndexService` indexes the same chunks into an in-process BM25 index and rebuilds from H2 on startup.
- `RagRetrievalService` runs confidence-gated metadata filtering, semantic retrieval, keyword fallback, reranking, retrieval caching, and low-confidence fallback.
- `PromptBuilder` injects compact retrieved context, recent session memory, and the current question into one strict grounded prompt.
- `AiOrchestratorService` ensures one logical LLM call per user message and blocks chat while ingestion is still running.

## Supported Ingestion

```http
POST http://localhost:8080/api/knowledge/documents
POST http://localhost:8080/api/ingest/url
GET  http://localhost:8080/api/ingest/status?sessionId={sessionId}
```

Supported upload types: PDF, DOCX, TXT, LOG, CSV, Java, XML, properties, YAML, JSON, plus Apache Tika fallback for other readable office/text formats.

The ingestion pipeline extracts text, normalizes it, chunks with overlap, generates embeddings once, persists vectors in H2, indexes BM25 terms in Lucene, generates a concise summary, and sets the session `activeDocumentId`.

## Resume And Code Retrieval

- Resume files are detected from filename/content and chunked by sections: Education, Experience, Skills, Certifications, Projects, and Summary.
- Factual questions about degree, year, university, percentage, and CGPA receive retrieval boosts for education chunks and neighboring chunk expansion.
- Java/config files use syntax-preserving chunking around classes, annotations, methods, endpoints, and configuration entries.
- Code filters are applied only when query intent confidence is high enough.

## Chat API

```http
POST http://localhost:8080/api/chat/ask
Content-Type: application/json

{
  "message": "When did Sachin complete his bachelor degree?",
  "sessionId": "current-session-id",
  "privateMode": false
}
```

Streaming endpoint:

```http
POST http://localhost:8080/api/chat/ask/stream
Content-Type: application/json
```

## H2 Console

Open `http://localhost:8080/h2-console`.

- JDBC URL: `jdbc:h2:file:./data/chatbotdb`
- User: `sa`
- Password: leave empty

## Important Properties

```properties
rag.top-k=3
rag.candidate-top-k=24
rag.local-candidate-limit=800
rag.allow-global-retrieval=false
rag.neighbor-expansion-limit=1
rag.lucene.rebuild-on-startup=true
rag.retrieval-cache-size=128
rag.max-context-chars=2200
ai.github.endpoint=https://models.github.ai/inference/chat/completions
ai.github.model=openai/gpt-4o-mini
ai.github.token=${GITHUB_TOKEN:}
ollama.embedding-model=nomic-embed-text
```

Reference SQL is in `src/main/resources/schema-enterprise.sql`.
