# Production RAG Refactor Notes

The refactor separates ingestion from chat and replaces external vector-store coupling with a local enterprise-safe retrieval stack.

## Dependency Changes

Added:

```text
org.apache.lucene:lucene-core
org.apache.lucene:lucene-analysis-common
org.apache.lucene:lucene-queryparser
```

Removed:

```text
external vector-store adapter dependencies
external vector-store health/provider classes
```

## Backend Changes

- `LuceneIndexService` indexes chunk content, source names, section titles, topics, and scope fields.
- `VectorStoreService` persists vectors as H2 JSON arrays and computes cosine similarity in Java.
- `RagRetrievalService` merges semantic and keyword candidates, performs low-confidence fallback, reranks results, and caches retrieval output.
- `TextChunker` supports recursive text chunking, resume-aware section chunking, and syntax-preserving code chunking.
- `AiOrchestratorService` scopes retrieval to `activeDocumentId` and keeps one logical LLM call per user message.
- `PromptBuilder` keeps compact grounded context and hides internal IDs.

## Retrieval Algorithm

```text
query embedding
-> active document/session scoped H2 candidate load
-> Lucene BM25 search in same scope
-> normalize BM25 and merge with cosine scores
-> factual/code/metadata boosts
-> rerank by lexical overlap, section match, exact phrases, and entity hints
-> expand previous/next chunks
-> strict grounded prompt
```

## Performance Improvements

- Document parsing and embedding remain async.
- Embeddings are generated once during ingestion.
- Retrieval is scoped and paginated.
- Lucene rebuilds from H2 instead of requiring a separate service.
- Prompt context is bounded by `rag.max-context-chars`.
- Retrieval cache avoids repeated query work for duplicate questions.

## Migration Steps

1. Pull the branch.
2. Start Ollama and pull `phi3:mini` plus `nomic-embed-text`.
3. Start the backend.
4. Re-upload older important documents so resume/code metadata and chunk boundaries are refreshed.
5. Ask questions in the same chat session where the document was uploaded.

## Validation

Check logs for:

- `rag query intent`
- `lucene search`
- `hybrid search completed`
- `rag reranking`
- `neighbor expansion completed`
- `promptChars`
- `llmMs`
