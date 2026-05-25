package com.example.internalchatbot.ai.retrieval;

import com.example.internalchatbot.ai.vectorstore.RetrievalFilter;
import com.example.internalchatbot.entity.EmbeddingMetadata;
import com.example.internalchatbot.repository.EmbeddingMetadataRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class LuceneIndexService {

    private static final Logger log = LoggerFactory.getLogger(LuceneIndexService.class);
    private static final int REBUILD_PAGE_SIZE = 500;
    private static final Set<String> STORED_ID_FIELD = Set.of("embeddingId");

    private final EmbeddingMetadataRepository embeddingMetadataRepository;
    private final StandardAnalyzer analyzer;
    private final Directory directory;
    private final IndexWriter indexWriter;
    private final boolean rebuildOnStartup;

    public LuceneIndexService(
            EmbeddingMetadataRepository embeddingMetadataRepository,
            @Value("${rag.lucene.rebuild-on-startup:true}") boolean rebuildOnStartup
    ) {
        this.embeddingMetadataRepository = embeddingMetadataRepository;
        this.rebuildOnStartup = rebuildOnStartup;
        this.analyzer = new StandardAnalyzer();
        this.directory = new ByteBuffersDirectory();
        try {
            IndexWriterConfig config = new IndexWriterConfig(analyzer);
            config.setSimilarity(new BM25Similarity());
            this.indexWriter = new IndexWriter(directory, config);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to initialize Lucene keyword index", ex);
        }
    }

    @PostConstruct
    void rebuildFromH2() {
        if (!rebuildOnStartup) {
            return;
        }
        long startedAt = System.nanoTime();
        int page = 0;
        int indexed = 0;
        try {
            while (true) {
                List<EmbeddingMetadata> batch = embeddingMetadataRepository.findByPrivateModeFalseOrderByCreatedAtDesc(
                        PageRequest.of(page++, REBUILD_PAGE_SIZE)
                );
                if (batch.isEmpty()) {
                    break;
                }
                for (EmbeddingMetadata metadata : batch) {
                    indexWithoutCommit(metadata);
                    indexed++;
                }
            }
            indexWriter.commit();
            log.info("lucene index rebuilt from H2 embeddings indexed={} totalMs={}", indexed, elapsedMillis(startedAt));
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to rebuild Lucene index from persisted embeddings", ex);
        }
    }

    public void index(EmbeddingMetadata metadata) {
        if (metadata == null || metadata.getId() == null || metadata.isPrivateMode()) {
            return;
        }
        try {
            indexWithoutCommit(metadata);
            log.debug("lucene indexed embeddingId={} documentId={} chunkIndex={}",
                    metadata.getId(),
                    metadata.getDocumentId(),
                    metadata.getChunkIndex()
            );
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to index chunk in Lucene", ex);
        }
    }

    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        try {
            indexWriter.deleteDocuments(new Term("documentId", String.valueOf(documentId)));
            indexWriter.commit();
            log.info("lucene deleted documentId={}", documentId);
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to delete Lucene document chunks", ex);
        }
    }

    public List<LuceneSearchResult> search(
            RetrievalScope scope,
            String queryText,
            RetrievalFilter filter,
            int limit
    ) {
        String cleanQuery = cleanQuery(queryText);
        if (cleanQuery.isBlank() || limit <= 0) {
            return List.of();
        }

        try (DirectoryReader reader = DirectoryReader.open(indexWriter)) {
            if (reader.numDocs() == 0) {
                return List.of();
            }
            Query query = buildQuery(scope, cleanQuery, filter);
            IndexSearcher searcher = new IndexSearcher(reader);
            searcher.setSimilarity(new BM25Similarity());
            TopDocs topDocs = searcher.search(query, Math.max(1, limit));
            List<LuceneSearchResult> results = new ArrayList<>();
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.doc(scoreDoc.doc, STORED_ID_FIELD);
                Long embeddingId = parseLong(doc.get("embeddingId"));
                if (embeddingId != null) {
                    results.add(new LuceneSearchResult(embeddingId, scoreDoc.score));
                }
            }
            log.info(
                    "lucene search scope={} activeDocumentId={} hits={} topScore={} queryChars={}",
                    scopeLabel(scope),
                    scope == null ? null : scope.activeDocumentId(),
                    results.size(),
                    results.isEmpty() ? 0 : results.getFirst().score(),
                    cleanQuery.length()
            );
            return results;
        } catch (IOException ex) {
            throw new UncheckedIOException("Unable to search Lucene index", ex);
        } catch (ParseException ex) {
            log.warn("Lucene query parse failed; keyword fallback skipped. query={}", cleanQuery, ex);
            return List.of();
        }
    }

    @PreDestroy
    void close() {
        try {
            indexWriter.close();
            directory.close();
            analyzer.close();
        } catch (IOException ex) {
            log.debug("Lucene index close failed", ex);
        }
    }

    private void indexWithoutCommit(EmbeddingMetadata metadata) throws IOException {
        if (metadata.getContentChunk() == null || metadata.getContentChunk().isBlank()) {
            return;
        }
        Document document = new Document();
        document.add(new StringField("embeddingId", String.valueOf(metadata.getId()), Field.Store.YES));
        addString(document, "documentId", metadata.getDocumentId() == null ? null : String.valueOf(metadata.getDocumentId()));
        addString(document, "sessionId", metadata.getSessionId());
        addString(document, "userKey", metadata.getUserKey());
        addString(document, "sourceType", metadata.getSourceType());
        addString(document, "documentType", metadata.getDocumentType());
        addString(document, "language", metadata.getLanguage());
        addString(document, "topic", metadata.getTopic());
        addString(document, "chunkIndex", metadata.getChunkIndex() == null ? null : String.valueOf(metadata.getChunkIndex()));
        document.add(new TextField("sourceName", defaultText(metadata.getSourceName()), Field.Store.NO));
        document.add(new TextField("sectionTitle", defaultText(metadata.getSectionTitle()), Field.Store.NO));
        document.add(new TextField("content", defaultText(metadata.getContentChunk()), Field.Store.NO));
        document.add(new StoredField("createdAt", metadata.getCreatedAt() == null ? "" : metadata.getCreatedAt().toString()));
        indexWriter.updateDocument(new Term("embeddingId", String.valueOf(metadata.getId())), document);
    }

    private Query buildQuery(RetrievalScope scope, String queryText, RetrievalFilter filter) throws ParseException {
        Map<String, Float> boosts = new HashMap<>();
        boosts.put("content", 1.0f);
        boosts.put("sectionTitle", 2.5f);
        boosts.put("sourceName", 1.3f);
        boosts.put("topic", 1.2f);
        MultiFieldQueryParser parser = new MultiFieldQueryParser(
                new String[]{"content", "sectionTitle", "sourceName", "topic"},
                analyzer,
                boosts
        );
        parser.setDefaultOperator(QueryParser.Operator.OR);

        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        builder.add(parser.parse(QueryParser.escape(queryText)), BooleanClause.Occur.MUST);
        addScopeFilters(builder, scope);
        addMetadataFilters(builder, filter);
        return builder.build();
    }

    private void addScopeFilters(BooleanQuery.Builder builder, RetrievalScope scope) {
        if (scope == null) {
            return;
        }
        if (scope.documentScoped()) {
            builder.add(new TermQuery(new Term("documentId", String.valueOf(scope.activeDocumentId()))), BooleanClause.Occur.FILTER);
            return;
        }
        if (scope.sessionScoped()) {
            builder.add(new TermQuery(new Term("sessionId", normalize(scope.sessionId()))), BooleanClause.Occur.FILTER);
            return;
        }
        if (scope.userScoped()) {
            builder.add(new TermQuery(new Term("userKey", normalize(scope.userKey()))), BooleanClause.Occur.FILTER);
        }
    }

    private void addMetadataFilters(BooleanQuery.Builder builder, RetrievalFilter filter) {
        if (filter == null || !filter.enforce()) {
            return;
        }
        addAnyFilter(builder, "documentType", filter.documentTypes());
        addAnyFilter(builder, "language", filter.languages());
        addAnyFilter(builder, "topic", filter.topics());
    }

    private void addAnyFilter(BooleanQuery.Builder builder, String field, Set<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        BooleanQuery.Builder any = new BooleanQuery.Builder();
        values.stream()
                .map(this::normalize)
                .filter(value -> !value.isBlank())
                .forEach(value -> any.add(new TermQuery(new Term(field, value)), BooleanClause.Occur.SHOULD));
        builder.add(any.build(), BooleanClause.Occur.FILTER);
    }

    private void addString(Document document, String field, String value) {
        String normalized = normalize(value);
        if (!normalized.isBlank()) {
            document.add(new StringField(field, normalized, Field.Store.NO));
        }
    }

    private String cleanQuery(String queryText) {
        if (queryText == null) {
            return "";
        }
        String compact = queryText.replaceAll("\\s+", " ").trim();
        return compact.length() <= 700 ? compact : compact.substring(0, 700);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String defaultText(String value) {
        return value == null ? "" : value;
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private String scopeLabel(RetrievalScope scope) {
        if (scope == null) {
            return "global";
        }
        if (scope.documentScoped()) {
            return "document";
        }
        if (scope.sessionScoped()) {
            return "session";
        }
        if (scope.userScoped()) {
            return "user";
        }
        return "global";
    }

    private long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
