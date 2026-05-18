package com.example.internalchatbot.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TextChunker {

    private static final List<String> SEPARATORS = List.of("\n\n", "\n", ". ", "; ", ", ", " ");

    private final int chunkSize;
    private final int overlap;
    private final TextNormalizer textNormalizer;

    public TextChunker(
            @Value("${rag.chunk-size:1000}") int chunkSize,
            @Value("${rag.chunk-overlap:200}") int overlap,
            TextNormalizer textNormalizer
    ) {
        this.chunkSize = Math.max(400, chunkSize);
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
            for (String text : chunkText(page.text())) {
                String normalized = textNormalizer.compact(text);
                if (normalized.isBlank()) {
                    continue;
                }
                Map<String, String> metadata = new LinkedHashMap<>(document.metadata());
                metadata.putAll(page.metadata());
                metadata.put("pageNumber", String.valueOf(page.pageNumber()));
                metadata.put("chunkIndex", String.valueOf(chunkIndex));
                metadata.put("parserName", document.parserName());
                chunks.add(new DocumentChunk(
                        normalized,
                        document.sourceName(),
                        document.sourceType(),
                        document.sourceUrl(),
                        page.pageNumber(),
                        page.sectionTitle(),
                        chunkIndex++,
                        estimateTokens(normalized),
                        ContentHash.sha256(normalized),
                        metadata
                ));
            }
        }
        return chunks;
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

    private String trimToChunkWindow(String text) {
        if (text.length() <= chunkSize + overlap) {
            return text;
        }
        return text.substring(text.length() - (chunkSize + overlap));
    }

    private int estimateTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }
}
