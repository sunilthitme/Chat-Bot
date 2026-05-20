# Internal Chatbot

Angular + Spring Boot internal chatbot with DB-first answers, Ollama, multi-session memory, private mode, modular production RAG, document ingestion, URL crawling, and persisted enterprise knowledge.

See [docs/enterprise-ai-architecture.md](docs/enterprise-ai-architecture.md) for the module-by-module architecture, API flow, schema, and production recommendations. See [docs/production-rag-refactor.md](docs/production-rag-refactor.md) for the latest refactor notes. See [docs/chroma-v2-rag.md](docs/chroma-v2-rag.md) for the ChromaDB API V2 and LangChain4j setup.

## Run Backend

```bash
cd backend
mvn spring-boot:run
```

Backend URL: `http://localhost:8080`

## Ollama And RAG

Ollama is enabled by default in `backend/src/main/resources/application.properties`.

Start Ollama locally and pull the configured models:

```bash
ollama pull phi3:mini
ollama pull nomic-embed-text
```

Important properties:

```properties
ollama.enabled=true
ollama.base-url=http://localhost:11434
ollama.model=phi3:mini
ollama.embedding-model=nomic-embed-text
chroma.enabled=true
chroma.base-url=http://localhost:8000
chroma.tenant-name=default
chroma.database-name=default
```

Chat requests run through `ai.rag.AiOrchestratorService`: internal DB lookup first, top-3 vector retrieval second, optimized prompt construction third, then one logical streamed Ollama response. Private-mode requests skip message persistence, embedding persistence, and future knowledge storage.

URL ingestion is separate from chat:

```http
POST /api/ingest/url
```

The URL is fetched, cleaned, chunked, embedded, and stored in the background. Chat never rereads webpages or sends raw HTML to Ollama.

## Run ChromaDB

```bash
docker compose up -d chromadb
```

Health check:

```bash
curl http://localhost:8000/api/v2/heartbeat
```

## Run Frontend

```bash
cd frontend
npm install
npm start
```

Frontend URL: `http://localhost:4200`

## API

```http
POST /api/chat/ask
Content-Type: application/json

{
  "message": "How to create RITM?",
  "privateMode": false
}
```

Response:

```json
{
  "reply": "Steps to create RITM: 1. Open the service portal...",
  "privateMode": false
}
```

Additional APIs include `/api/sessions`, `/api/knowledge/documents`, `/api/ingest/url`, and `/api/chat/ask/stream`.
