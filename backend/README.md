# Internal Chatbot Backend

Spring Boot REST API for a DB-first enterprise chatbot with session memory, private mode, page-aware document ingestion, recursive URL crawling, Ollama generation, LangChain4j embeddings, ChromaDB vector sync, and local persisted vector metadata.

## Run

```bash
mvn spring-boot:run
```

## Ollama LLM And Embeddings

Ollama is enabled by default. Start Ollama and pull both models:

```bash
ollama pull llama3.2
ollama pull nomic-embed-text
```

Chat requests always check the internal database first.

- If the database has a matching answer, the prompt tells Ollama to prefer it.
- If the database has no answer, RAG context from stored documents and crawled URLs is provided.
- If Ollama is unavailable, the service falls back to the DB answer or the default not-found message.
- If private mode is enabled, messages and embeddings are not persisted.

## Ask API

```http
POST http://localhost:8080/api/chat/ask
Content-Type: application/json

{
  "message": "How to create RITM?",
  "sessionId": "",
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
POST http://localhost:8080/api/knowledge/urls
```

Supported upload types: PDF, DOCX, TXT, LOG, CSV, plus Apache Tika fallback for other readable office/text formats.

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

Replace the H2 datasource properties in `src/main/resources/application.properties` with:

```properties
spring.datasource.url=jdbc:mysql://localhost:3306/internal_chatbot
spring.datasource.username=root
spring.datasource.password=your_password
spring.jpa.hibernate.ddl-auto=update
```

Reference SQL for the enterprise tables is in `src/main/resources/schema-enterprise.sql`. The production RAG refactor notes are in `../docs/production-rag-refactor.md`.
