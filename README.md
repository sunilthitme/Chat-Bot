# Internal Chatbot

Angular + Spring Boot internal chatbot with DB-first answers, Ollama, multi-session memory, private mode, async ingestion, and an enterprise-safe RAG stack that runs without external vector databases.

This branch is designed for restricted corporate environments:

- No PostgreSQL requirement
- No ChromaDB requirement
- No Docker requirement
- No Python service requirement
- H2 stores embeddings as JSON arrays
- Apache Lucene provides in-process BM25 keyword search
- Java computes cosine similarity over persisted vectors
- Ollama provides `phi3:mini` and `nomic-embed-text`

See [docs/restricted-corporate-rag.md](docs/restricted-corporate-rag.md) for the architecture, flow diagrams, schema, retrieval algorithm, and production notes.

## Run Backend

```bash
cd backend
mvn spring-boot:run
```

Backend URL: `http://localhost:8080`

## Ollama

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
rag.allow-global-retrieval=false
rag.top-k=3
rag.chunk-size=800
rag.chunk-overlap=150
```

## RAG Flow

Ingestion and chat are separate:

```text
Upload or URL
-> extract text once
-> section/code/resume-aware chunking
-> generate embeddings once
-> store chunks and vectors in H2
-> index chunks in Lucene
-> summarize content
-> set activeDocumentId on the session
```

```text
Chat question
-> build query from current question + recent memory
-> embed query once
-> search active document/session scope
-> merge H2 cosine similarity + Lucene BM25
-> rerank, expand neighboring chunks, keep compact context
-> one streamed Ollama response
```

Chat never rereads uploaded files, never crawls URLs during question answering, and never regenerates stored embeddings during chat.

## Run Frontend

```bash
cd frontend
npm install
npm start
```

Frontend URL: `http://localhost:4200`

## Main APIs

```http
POST /api/chat/ask
POST /api/chat/ask/stream
POST /api/knowledge/documents
POST /api/ingest/url
GET  /api/ingest/status?sessionId={sessionId}
GET  /api/sessions
```

Chat responses do not expose vector IDs, embedding IDs, or internal session metadata.
