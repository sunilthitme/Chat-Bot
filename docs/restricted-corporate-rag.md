# Restricted Corporate RAG Implementation

This implementation is built for environments where teams cannot install external vector databases, database servers, container runtimes, or Python services.

## Supported Stack

- Java 21
- Spring Boot
- H2 file database
- Apache Lucene in-process BM25 index
- Java cosine similarity over H2-stored vector JSON
- Ollama `phi3:mini`
- Ollama `nomic-embed-text`
- Angular frontend

## No External Vector Infrastructure

The application does not create a client for external vector storage. Uploaded knowledge is stored locally in:

- `uploaded_documents` for extracted text, summaries, status, and active document metadata
- `embeddings_metadata` for chunk text, metadata, and embedding vectors as JSON arrays
- an in-process Lucene index rebuilt from H2 during startup

## Core Services

```text
DocumentExtractionService    PDF/DOCX/TXT/CSV/code extraction
TextChunker                  overlap, resume-aware, code-aware chunking
EmbeddingService             nomic-embed-text embedding and cache
VectorStoreService           H2 vector JSON persistence and cosine similarity
LuceneIndexService           BM25 keyword indexing/search
RagRetrievalService          hybrid retrieval, fallback, reranking, cache
ConversationMemoryService    recent session memory and retrieval query
PromptBuilder                strict grounded prompt with compact context
AiOrchestratorService        one logical LLM call and streaming orchestration
```

## Cosine Similarity

`VectorStoreService` deserializes stored `vector_json` and calculates:

```text
cosine(a,b) = dot(a,b) / (norm(a) * norm(b))
```

The final hybrid score blends:

- vector similarity
- normalized Lucene BM25 score
- lexical keyword overlap
- metadata boosts
- active session boosts
- factual resume boosts

## Fallback Strategy

```text
metadata-filtered retrieval
-> if weak, semantic retrieval without enforced filters
-> if weak, keyword-hybrid retrieval with boost-only intent metadata
-> rerank and expand neighboring chunks
```

Low confidence is controlled by `rag.low-confidence-score`.

## Memory

- `activeDocumentId` and `activeDocumentName` are stored on the chat session after ingestion completes.
- Recent messages are used for pronoun/follow-up resolution.
- Private mode skips message storage and embedding storage.
- Retrieval is scoped to the active document first, then session/user scope only when appropriate.

## Configuration

```properties
rag.top-k=3
rag.candidate-top-k=24
rag.local-candidate-limit=800
rag.allow-global-retrieval=false
rag.intent-filter-threshold=0.72
rag.low-confidence-score=0.75
rag.neighbor-expansion-limit=1
rag.expanded-candidate-limit=32
rag.lucene.rebuild-on-startup=true
rag.retrieval-cache-size=128
rag.max-context-chars=2200
rag.max-chunk-context-chars=650
ollama.model=phi3:mini
ollama.embedding-model=nomic-embed-text
ollama.num-predict=256
```

## Validation Checklist

- Upload a resume and wait for indexing completion.
- Ask a factual question from the resume, such as a degree/year/university question.
- Confirm logs show `activeDocumentId`, Lucene hits, hybrid scores, reranking scores, and neighbor expansion.
- Confirm no unrelated old webpages are retrieved when an active document exists.
- Confirm prompt size stays near the configured context limit.
