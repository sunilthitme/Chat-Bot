# Internal Chatbot Backend

Spring Boot REST API for a DB-first enterprise chatbot with modular AI packages, session memory, private mode, page-aware document ingestion, recursive URL crawling, Ollama generation, LangChain4j embeddings, ChromaDB vector sync, and local persisted vector metadata.

## Run

```bash
mvn spring-boot:run
```

## Ollama LLM And Embeddings

Ollama is enabled by default. Start Ollama and pull both models:

```bash
ollama pull phi3:mini
ollama pull nomic-embed-text
```

Chat requests always check the internal database first.

- If the database has a matching answer, the prompt tells Ollama to prefer it.
- If the database has no answer, RAG context from stored documents and crawled URLs is provided.
- If Ollama is unavailable, the service falls back to the DB answer or the default not-found message.
- If private mode is enabled, messages and embeddings are not persisted.
- Chat requests never fetch URLs, parse raw HTML, or regenerate document embeddings.
- RAG retrieves Chroma and local hybrid candidates, reranks them against the question, and sends only the best 5 chunks to Ollama.
- Code and Spring Boot questions receive metadata boosts for Java, API, controller, service, and configuration chunks.
- The prompt instructs Ollama to answer only from the DB answer and retrieved chunks. Missing context returns `Information not found in the indexed knowledge.`

## Ask API

```http
POST http://localhost:8080/api/chat/ask
Content-Type: application/json

{
  "message": "How to create RITM?",
  "privateMode": false
}
```

Streaming endpoint:

```http
POST http://localhost:8080/api/chat/ask/stream
Content-Type: application/json
```

Document upload and URL ingestion:

```http
POST http://localhost:8080/api/knowledge/documents
POST http://localhost:8080/api/ingest/url
GET  http://localhost:8080/api/ingest/status?sessionId={sessionId}
```

Supported upload types: PDF, DOCX, TXT, LOG, CSV, Java, XML, properties, YAML, JSON, plus Apache Tika fallback for other readable office/text formats.
URL ingestion accepts public HTTP/HTTPS sites, follows redirects, reads sitemaps, crawls same-host links, and blocks private/local hosts by default with `url.block-private-hosts=true`.
HTML extraction uses Readability4J first, then Boilerpipe, Apache Tika, structured Jsoup cleanup, and an optional Trafilatura CLI fallback when `url.trafilatura.enabled=true`.
Chat responses do not expose retrieved source metadata to the frontend.

Document upload and `POST /api/ingest/url` return immediately with a queued status. The background ingestion executor extracts content, cleans it, chunks it with `rag.chunk-size=800` and `rag.chunk-overlap=150`, embeds once, stores vectors, then generates a concise summary. Java and config files use syntax-preserving code chunking around classes, annotations, methods, endpoints, and configuration entries. The frontend polls `/api/ingest/status` and blocks questions until indexing reaches `COMPLETED`, `NO_EMBEDDINGS`, or `FAILED`. Chat then uses hybrid similarity and keyword search only.

## ChromaDB

```bash
docker compose up -d chromadb
curl http://localhost:8000/api/v2/heartbeat
```

## Direct LLM API

```http
POST http://localhost:8080/api/llm/generate
Content-Type: application/json

{
  "message": "Explain password reset steps"
}
```

This endpoint returns `503 Service Unavailable` when `ollama.enabled=false` or Ollama cannot be reached.

## H2 Console

Open `http://localhost:8080/h2-console`.

- JDBC URL: `jdbc:h2:file:./data/chatbotdb`
- User: `sa`
- Password: leave empty

## Production Database

Replace the H2 datasource properties in `src/main/resources/application.properties` with MySQL:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/internal_chatbot
spring.datasource.username=root
spring.datasource.password=your_password
spring.jpa.hibernate.ddl-auto=update
```

Or PostgreSQL:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/internal_chatbot
spring.datasource.username=postgres
spring.datasource.password=your_password
spring.jpa.hibernate.ddl-auto=validate
```

Reference SQL for the enterprise tables is in `src/main/resources/schema-enterprise.sql`. The production RAG refactor notes are in `../docs/production-rag-refactor.md`.
