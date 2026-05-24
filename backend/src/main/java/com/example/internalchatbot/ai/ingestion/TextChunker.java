package com.example.internalchatbot.ai.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class TextChunker {

    private static final List<String> SEPARATORS = List.of("\n\n", "\n", ". ", "; ", ", ", " ");

    private final int chunkSize;
    private final int overlap;
    private final TextNormalizer textNormalizer;

    public TextChunker(
            @Value("${rag.chunk-size:800}") int chunkSize,
            @Value("${rag.chunk-overlap:150}") int overlap,
            TextNormalizer textNormalizer
    ) {
        this.chunkSize = Math.max(300, chunkSize);
        this.overlap = Math.max(0, Math.min(overlap, this.chunkSize / 2));
        this.textNormalizer = textNormalizer;
    }

    public List<String> chunk(String text) {
        return chunkText(textNormalizer.normalize(text)).stream()
                .map(textNormalizer::compact)
                .filter(chunk -> !chunk.isBlank())
                .toList();
    }

    public List<DocumentChunk> chunk(ExtractedDocument document) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;
        for (ExtractedPage page : document.pages()) {
            Map<String, String> baseMetadata = new LinkedHashMap<>(document.metadata());
            baseMetadata.putAll(page.metadata());
            baseMetadata.put("source", document.sourceName());
            baseMetadata.put("pageNumber", String.valueOf(page.pageNumber()));
            baseMetadata.put("parserName", document.parserName());
            baseMetadata.putIfAbsent("uploadDate", Instant.now().toString());

            String documentType = metadataValue(baseMetadata, "documentType", inferDocumentType(document));
            String language = metadataValue(baseMetadata, "language", inferLanguage(documentType, document.sourceName()));
            String topic = metadataValue(baseMetadata, "topic", inferTopic(document.sourceName(), page.sectionTitle()));
            boolean code = isCode(documentType, language);

            for (ChunkPiece piece : chunkPage(page.text(), page.sectionTitle(), code)) {
                String normalized = code ? textNormalizer.normalize(piece.text()) : textNormalizer.compact(piece.text());
                if (normalized.isBlank()) {
                    continue;
                }
                Map<String, String> metadata = new LinkedHashMap<>(baseMetadata);
                metadata.put("chunkIndex", String.valueOf(chunkIndex));
                metadata.put("documentType", documentType);
                metadata.put("language", language);
                metadata.put("topic", topic);
                chunks.add(new DocumentChunk(
                        normalized,
                        document.sourceName(),
                        document.sourceType(),
                        document.sourceUrl(),
                        page.pageNumber(),
                        defaultIfBlank(piece.sectionTitle(), page.sectionTitle()),
                        documentType,
                        language,
                        topic,
                        chunkIndex++,
                        estimateTokens(normalized),
                        ContentHash.sha256(normalized),
                        metadata
                ));
            }
        }
        return chunks;
    }

    private List<ChunkPiece> chunkPage(String text, String sectionTitle, boolean code) {
        if (code) {
            return chunkCode(text, sectionTitle);
        }
        return chunkText(text).stream()
                .map(chunk -> new ChunkPiece(chunk, sectionTitle))
                .toList();
    }

    private List<String> chunkText(String rawText) {
        String text = textNormalizer.normalize(rawText);
        if (text.isBlank()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        splitRecursive(text, chunks);
        return addOverlap(chunks);
    }

    private List<ChunkPiece> chunkCode(String rawText, String defaultTitle) {
        String text = textNormalizer.normalize(rawText);
        if (text.isBlank()) {
            return List.of();
        }

        List<ChunkPiece> pieces = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentTitle = defaultIfBlank(defaultTitle, "Code");
        for (String line : text.split("\\R", -1)) {
            String trimmed = line.trim();
            boolean boundary = isCodeBoundary(trimmed);
            if (boundary && current.length() > 0 && current.length() >= Math.min(240, chunkSize / 2)) {
                flushCodePiece(pieces, current, currentTitle);
                currentTitle = codeTitle(trimmed);
            } else if (boundary && current.length() == 0) {
                currentTitle = codeTitle(trimmed);
            }

            if (current.length() + line.length() + 1 > chunkSize + overlap && current.length() > 0) {
                flushCodePiece(pieces, current, currentTitle);
            }
            current.append(line).append('\n');
        }
        flushCodePiece(pieces, current, currentTitle);
        return addCodeOverlap(pieces);
    }

    private void splitRecursive(String text, List<String> chunks) {
        String normalized = textNormalizer.normalize(text);
        if (normalized.length() <= chunkSize) {
            chunks.add(normalized);
            return;
        }

        String separator = chooseSeparator(normalized);
        if (separator.isEmpty()) {
            splitHard(normalized, chunks);
            return;
        }

        String[] parts = normalized.split(java.util.regex.Pattern.quote(separator));
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            String candidate = current.isEmpty() ? part : current + separator + part;
            if (candidate.length() > chunkSize && !current.isEmpty()) {
                splitRecursive(current.toString(), chunks);
                current.setLength(0);
            }
            if (!current.isEmpty()) {
                current.append(separator);
            }
            current.append(part);
        }
        if (!current.isEmpty()) {
            splitRecursive(current.toString(), chunks);
        }
    }

    private String chooseSeparator(String text) {
        for (String separator : SEPARATORS) {
            if (text.contains(separator)) {
                return separator;
            }
        }
        return "";
    }

    private void splitHard(String text, List<String> chunks) {
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + chunkSize, text.length());
            chunks.add(text.substring(start, end));
            start = end;
        }
    }

    private List<String> addOverlap(List<String> chunks) {
        if (chunks.size() <= 1 || overlap == 0) {
            return chunks;
        }
        List<String> withOverlap = new ArrayList<>();
        String previous = "";
        for (String chunk : chunks) {
            String prefix = previous.length() <= overlap ? previous : previous.substring(previous.length() - overlap);
            String merged = prefix.isBlank() ? chunk : prefix + "\n" + chunk;
            withOverlap.add(textNormalizer.normalize(trimToChunkWindow(merged)));
            previous = chunk;
        }
        return withOverlap;
    }

    private List<ChunkPiece> addCodeOverlap(List<ChunkPiece> pieces) {
        if (pieces.size() <= 1 || overlap == 0) {
            return pieces;
        }
        List<ChunkPiece> withOverlap = new ArrayList<>();
        String previous = "";
        for (ChunkPiece piece : pieces) {
            String prefix = tailByLine(previous, overlap);
            String merged = prefix.isBlank() ? piece.text() : prefix + "\n" + piece.text();
            withOverlap.add(new ChunkPiece(textNormalizer.normalize(trimToChunkWindow(merged)), piece.sectionTitle()));
            previous = piece.text();
        }
        return withOverlap;
    }

    private void flushCodePiece(List<ChunkPiece> pieces, StringBuilder current, String title) {
        String chunk = textNormalizer.normalize(current.toString());
        if (!chunk.isBlank()) {
            pieces.add(new ChunkPiece(chunk, title));
        }
        current.setLength(0);
    }

    private String trimToChunkWindow(String text) {
        if (text.length() <= chunkSize + overlap) {
            return text;
        }
        return text.substring(text.length() - (chunkSize + overlap));
    }

    private boolean isCodeBoundary(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        return line.startsWith("@")
                || line.matches("(?i).*(class|interface|enum|record)\\s+[A-Za-z0-9_]+.*")
                || line.matches("(?i).*(public|private|protected)\\s+.*\\([^;]*\\).*")
                || line.matches("(?i).*(@Bean|@GetMapping|@PostMapping|@PutMapping|@DeleteMapping|@RequestMapping).*");
    }

    private String codeTitle(String line) {
        if (line == null || line.isBlank()) {
            return "Code";
        }
        String compact = line.replaceAll("\\s+", " ").trim();
        return compact.length() <= 160 ? compact : compact.substring(0, 157) + "...";
    }

    private String tailByLine(String text, int maxChars) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String tail = text.length() <= maxChars ? text : text.substring(text.length() - maxChars);
        int lineBreak = tail.indexOf('\n');
        return lineBreak >= 0 ? tail.substring(lineBreak + 1) : tail;
    }

    private boolean isCode(String documentType, String language) {
        String normalizedType = normalizeLabel(documentType);
        String normalizedLanguage = normalizeLabel(language);
        return "code".equals(normalizedType)
                || "java".equals(normalizedLanguage)
                || "xml".equals(normalizedLanguage)
                || "yaml".equals(normalizedLanguage)
                || "properties".equals(normalizedLanguage)
                || "json".equals(normalizedLanguage);
    }

    private String inferDocumentType(ExtractedDocument document) {
        String sourceName = document.sourceName() == null ? "" : document.sourceName().toLowerCase(Locale.ROOT);
        if (sourceName.endsWith(".java") || sourceName.endsWith(".xml") || sourceName.endsWith(".properties")
                || sourceName.endsWith(".yml") || sourceName.endsWith(".yaml") || sourceName.endsWith(".json")) {
            return "code";
        }
        if ("url".equalsIgnoreCase(document.sourceType())) {
            return "web";
        }
        int dot = sourceName.lastIndexOf('.');
        return dot >= 0 ? sourceName.substring(dot + 1) : document.sourceType();
    }

    private String inferLanguage(String documentType, String sourceName) {
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".java")) {
            return "java";
        }
        if (name.endsWith(".xml")) {
            return "xml";
        }
        if (name.endsWith(".yml") || name.endsWith(".yaml")) {
            return "yaml";
        }
        if (name.endsWith(".properties")) {
            return "properties";
        }
        if (name.endsWith(".json")) {
            return "json";
        }
        return "code".equalsIgnoreCase(documentType) ? "code" : "text";
    }

    private String inferTopic(String sourceName, String sectionTitle) {
        String text = (defaultIfBlank(sourceName, "") + " " + defaultIfBlank(sectionTitle, "")).toLowerCase(Locale.ROOT);
        if (text.contains("spring") || text.contains("boot") || text.contains("controller")
                || text.contains("service") || text.contains("repository") || text.contains("configuration")) {
            return "spring-boot";
        }
        if (text.contains("api") || text.contains("rest")) {
            return "api";
        }
        if (text.contains("java")) {
            return "java";
        }
        return "general";
    }

    private String metadataValue(Map<String, String> metadata, String key, String fallback) {
        String value = metadata.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String normalizeLabel(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private int estimateTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }

    private record ChunkPiece(String text, String sectionTitle) {
    }
}
