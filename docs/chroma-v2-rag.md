# ChromaDB API V2 RAG Setup

This project uses the latest published LangChain4j release line available in Maven Central for this branch:

```xml
<langchain4j.version>1.14.1</langchain4j.version>
<langchain4j.chroma.version>1.14.1-beta24</langchain4j.chroma.version>
```

`langchain4j-ollama` is on the stable `1.14.1` artifact. `langchain4j-chroma` is published with the beta suffix, but `1.14.1-beta24` is the latest non-snapshot Chroma integration artifact from the same release line.

## Why The Old Startup Failed

The previous `VectorStoreService` created `ChromaEmbeddingStore` in its constructor and did not specify the API version:

```java
ChromaEmbeddingStore.builder()
        .baseUrl(chromaBaseUrl)
        .collectionName(collectionName)
        .build();
```

That allowed LangChain4j to use the older V1 path during bean creation. Latest ChromaDB exposes health at `/api/v2/heartbeat` and uses tenant/database scoped V2 collection routes. Because the store was created inside the Spring bean constructor, any Chroma connection or API mismatch failed the whole application startup.

## Correct Chroma V2 Builder

The store is now created lazily by `ChromaEmbeddingStoreProvider` only after `/api/v2/heartbeat` succeeds:

```java
ChromaEmbeddingStore.builder()
        .apiVersion(ChromaApiVersion.V2)
        .baseUrl(properties.baseUrl())
        .tenantName(properties.tenantName())
        .databaseName(properties.databaseName())
        .collectionName(properties.collectionName())
        .timeout(properties.timeout())
        .logRequests(properties.logRequests())
        .logResponses(properties.logResponses())
        .build();
```

## Application Properties

```properties
ollama.enabled=true
ollama.base-url=http://localhost:11434
ollama.model=llama3.2
ollama.embedding-model=nomic-embed-text
ollama.embedding-timeout=60s

chroma.enabled=true
chroma.base-url=http://localhost:8000
chroma.collection-name=internal_chatbot_knowledge
chroma.tenant-name=default
chroma.database-name=default
chroma.timeout=10s
chroma.health-check-timeout=2s
chroma.health-check-retries=3
chroma.retry-delay=10s
chroma.log-requests=false
chroma.log-responses=false
```

## Example Embedding Insertion

The production insertion path is `KnowledgeIngestionService -> EmbeddingService -> VectorStoreService`:

```java
List<Double> vector = embeddingService.embed(chunk);
vectorStoreService.store(
        "document",
        sessionId,
        documentId,
        sourceName,
        "file",
        chunk,
        vector,
        false
);
```

`VectorStoreService` always saves local metadata first, then tries Chroma. If Chroma is unhealthy, the request succeeds with local fallback.

## Example Similarity Search

```java
List<Double> queryVector = embeddingService.embed(userQuestion);
List<VectorSearchResult> results = vectorStoreService.search(queryVector, 5);
```

The search path tries Chroma V2 first. If the Chroma request fails or returns no matches, it falls back to local cosine similarity over persisted vectors.

## Debugging Chroma Issues

Use these checks in order:

```bash
curl http://localhost:8000/api/v2/heartbeat
curl http://localhost:8000/api/v2/version
```

Then verify the app properties:

- `chroma.base-url` must not include `/api/v1` or `/api/v2`.
- For local loopback URLs, the health client tries `localhost`, `127.0.0.1`, and `[::1]`, then uses the first base URL that passes heartbeat.
- `chroma.tenant-name` should be `default` for local Chroma unless you created another tenant.
- `chroma.database-name` should be `default` for local Chroma unless you created another database.
- `chroma.collection-name` should be stable between restarts.
- Turn on `chroma.log-requests=true` only during debugging because it can expose document text in logs.

## Enterprise Practices

- Keep Chroma initialization lazy; never let vector-store downtime block Spring Boot startup.
- Health-check `/api/v2/heartbeat` before creating API clients.
- Persist source metadata in the application database before vector sync.
- Use retries with backoff and fallback retrieval.
- Keep private-mode data out of Chroma and local embedding tables.
- Add metrics for heartbeat failures, sync failures, search fallbacks, and embedding latency.
- Use stable tenant/database names per environment.
