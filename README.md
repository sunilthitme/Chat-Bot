# Internal Chatbot

Angular + Spring Boot internal chatbot with DB-first answers, Ollama, multi-session memory, private mode, document and URL ingestion, ServiceNow incident analysis, and RAG over persisted enterprise knowledge.

See [docs/enterprise-ai-architecture.md](docs/enterprise-ai-architecture.md) for the module-by-module architecture, API flow, schema, and production recommendations.

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
ollama pull llama3.2
ollama pull nomic-embed-text
```

Important properties:

```properties
ollama.enabled=true
ollama.base-url=http://localhost:11434
ollama.model=llama3.2
ollama.embedding-model=nomic-embed-text
chroma.enabled=true
chroma.base-url=http://localhost:8000
```

Chat requests check the internal DB first, then retrieved vector knowledge, then Ollama. Private-mode requests skip message persistence, embedding persistence, and future knowledge storage.

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
  "sessionId": "",
  "privateMode": false
}
```

Response:

```json
{
  "sessionId": "generated-session-id",
  "reply": "Steps to create RITM: 1. Open the service portal...",
  "privateMode": false,
  "sources": []
}
```

Additional APIs include `/api/sessions`, `/api/knowledge/documents`, `/api/knowledge/urls`, `/api/incidents/analyze`, and `/api/chat/ask/stream`.
