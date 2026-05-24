package com.example.internalchatbot.ai.ingestion;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DocumentExtractionService {

    private static final int TEXT_PAGE_SIZE = 6_000;

    private final long maxUploadBytes;
    private final TextNormalizer textNormalizer;
    private final AutoDetectParser tikaParser;

    public DocumentExtractionService(
            @Value("${security.max-upload-bytes}") long maxUploadBytes,
            TextNormalizer textNormalizer
    ) {
        this.maxUploadBytes = maxUploadBytes;
        this.textNormalizer = textNormalizer;
        this.tikaParser = new AutoDetectParser();
    }

    public ExtractedDocument extract(MultipartFile file) {
        validate(file);

        try {
            return extract(file.getOriginalFilename(), file.getContentType(), file.getBytes());
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read uploaded document", ex);
        }
    }

    public ExtractedDocument extract(String originalFilename, String contentType, byte[] bytes) {
        validateBytes(bytes);
        String filename = sanitizeFilename(originalFilename);
        String extension = extension(filename);

        return switch (extension) {
            case "pdf" -> extractPdf(filename, contentType, bytes);
            case "docx" -> extractDocx(filename, contentType, bytes);
            case "csv" -> extractCsv(filename, contentType, bytes);
            case "txt", "log" -> extractText(filename, contentType, bytes, extension);
            case "java", "xml", "yml", "yaml", "properties", "json" -> extractCode(filename, contentType, bytes, extension);
            default -> extractWithTika(filename, contentType, bytes, "tika-fallback");
        };
    }

    private ExtractedDocument extractPdf(String filename, String contentType, byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            List<ExtractedPage> pages = new ArrayList<>();
            int pageCount = document.getNumberOfPages();
            for (int page = 1; page <= pageCount; page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = textNormalizer.normalize(stripper.getText(document));
                if (!text.isBlank()) {
                    pages.add(new ExtractedPage(page, detectSectionTitle(text), text, Map.of("parser", "pdfbox")));
                }
            }

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("pageCount", String.valueOf(pageCount));
            metadata.put("ocrStatus", pages.isEmpty() ? "placeholder_required_for_scanned_pdf" : "not_required");
            return toDocument(filename, "file", null, contentType, "pdfbox-page-aware", metadata, pages);
        } catch (IOException ex) {
            return extractWithTika(filename, contentType, bytes, "tika-pdf-fallback");
        }
    }

    private ExtractedDocument extractDocx(String filename, String contentType, byte[] bytes) {
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            List<ExtractedPage> pages = new ArrayList<>();
            String currentSection = null;
            StringBuilder sectionText = new StringBuilder();
            int page = 1;

            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = textNormalizer.normalize(paragraph.getText());
                if (text.isBlank()) {
                    continue;
                }
                if (isHeading(paragraph, text)) {
                    page = flushDocxSection(pages, page, currentSection, sectionText);
                    currentSection = text;
                } else {
                    sectionText.append(text).append('\n');
                }
            }
            flushDocxSection(pages, page, currentSection, sectionText);

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("paragraphCount", String.valueOf(document.getParagraphs().size()));
            return toDocument(filename, "file", null, contentType, "poi-docx-structured", metadata, pages);
        } catch (IOException ex) {
            return extractWithTika(filename, contentType, bytes, "tika-docx-fallback");
        }
    }

    private int flushDocxSection(List<ExtractedPage> pages, int page, String sectionTitle, StringBuilder sectionText) {
        String text = textNormalizer.normalize(sectionText.toString());
        if (!text.isBlank()) {
            pages.add(new ExtractedPage(page, sectionTitle, text, Map.of("parser", "poi")));
            page++;
        }
        sectionText.setLength(0);
        return page;
    }

    private ExtractedDocument extractCsv(String filename, String contentType, byte[] bytes) {
        String raw = new String(bytes, StandardCharsets.UTF_8);
        try (CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setTrim(true)
                .get()
                .parse(new StringReader(raw))) {
            List<ExtractedPage> pages = new ArrayList<>();
            List<String> headers = parser.getHeaderNames();
            StringBuilder pageText = new StringBuilder();
            int rowIndex = 1;
            int page = 1;
            for (CSVRecord record : parser) {
                String rowText = formatCsvRow(headers, record, rowIndex);
                if (pageText.length() + rowText.length() > TEXT_PAGE_SIZE && !pageText.isEmpty()) {
                    pages.add(new ExtractedPage(page++, "CSV rows", textNormalizer.normalize(pageText.toString()), Map.of("parser", "commons-csv")));
                    pageText.setLength(0);
                }
                pageText.append(rowText).append('\n');
                rowIndex++;
            }
            if (!pageText.isEmpty()) {
                pages.add(new ExtractedPage(page, "CSV rows", textNormalizer.normalize(pageText.toString()), Map.of("parser", "commons-csv")));
            }

            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put("headers", String.join(",", headers));
            metadata.put("rowCount", String.valueOf(Math.max(0, rowIndex - 1)));
            return toDocument(filename, "file", null, contentType, "commons-csv", metadata, pages);
        } catch (IOException | IllegalArgumentException ex) {
            return extractText(filename, contentType, bytes, "csv-text-fallback");
        }
    }

    private ExtractedDocument extractText(String filename, String contentType, byte[] bytes, String parserName) {
        String text = textNormalizer.normalize(new String(bytes, StandardCharsets.UTF_8));
        List<ExtractedPage> pages = splitLongTextIntoPages(text, parserName);
        return toDocument(filename, "file", null, contentType, parserName, Map.of(), pages);
    }

    private ExtractedDocument extractCode(String filename, String contentType, byte[] bytes, String extension) {
        String text = textNormalizer.normalize(new String(bytes, StandardCharsets.UTF_8));
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("documentType", "code");
        metadata.put("language", codeLanguage(extension));
        metadata.put("topic", inferTopic(filename));
        metadata.put("filename", filename);
        metadata.put("source", filename);
        return toDocument(
                filename,
                "file",
                null,
                contentType == null ? codeContentType(extension) : contentType,
                "code-aware-" + extension,
                metadata,
                splitCodeIntoSections(text, extension)
        );
    }

    private ExtractedDocument extractWithTika(String filename, String contentType, byte[] bytes, String parserName) {
        try {
            Metadata metadata = new Metadata();
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            ContentHandler handler = new BodyContentHandler(-1);
            tikaParser.parse(new ByteArrayInputStream(bytes), handler, metadata, new ParseContext());
            String text = textNormalizer.normalize(handler.toString());
            Map<String, String> parsedMetadata = metadata.names().length == 0
                    ? Map.of()
                    : java.util.Arrays.stream(metadata.names())
                            .collect(Collectors.toMap(name -> name, metadata::get, (left, right) -> left, LinkedHashMap::new));
            return toDocument(filename, "file", null, contentType, parserName, parsedMetadata, splitLongTextIntoPages(text, parserName));
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to extract document text with fallback parser", ex);
        }
    }

    private ExtractedDocument toDocument(
            String sourceName,
            String sourceType,
            String sourceUrl,
            String mediaType,
            String parserName,
            Map<String, String> metadata,
            List<ExtractedPage> pages
    ) {
        List<ExtractedPage> cleanedPages = pages.stream()
                .filter(page -> page.text() != null && !page.text().isBlank())
                .toList();
        if (cleanedPages.isEmpty()) {
            throw new IllegalArgumentException("No readable text was extracted. OCR support is required for scanned documents.");
        }
        String combinedText = cleanedPages.stream()
                .map(ExtractedPage::text)
                .collect(Collectors.joining("\n\n"));
        Map<String, String> enrichedMetadata = enrichMetadata(sourceName, sourceType, mediaType, metadata, cleanedPages);
        return new ExtractedDocument(
                sourceName,
                sourceType,
                sourceUrl,
                mediaType == null ? "application/octet-stream" : mediaType,
                parserName,
                ContentHash.sha256(textNormalizer.compact(combinedText)),
                enrichedMetadata,
                cleanedPages
        );
    }

    private Map<String, String> enrichMetadata(
            String sourceName,
            String sourceType,
            String mediaType,
            Map<String, String> metadata,
            List<ExtractedPage> pages
    ) {
        Map<String, String> enriched = new LinkedHashMap<>(metadata);
        enriched.putIfAbsent("filename", sourceName);
        enriched.putIfAbsent("source", sourceName);
        enriched.putIfAbsent("documentType", inferDocumentType(sourceName, sourceType, mediaType));
        enriched.putIfAbsent("language", inferLanguage(sourceName, enriched.get("documentType")));
        enriched.putIfAbsent("topic", inferTopic(sourceName, pages));
        return enriched;
    }

    private List<ExtractedPage> splitLongTextIntoPages(String text, String parserName) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<ExtractedPage> pages = new ArrayList<>();
        int page = 1;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + TEXT_PAGE_SIZE, text.length());
            if (end < text.length()) {
                int paragraphBreak = text.lastIndexOf("\n\n", end);
                if (paragraphBreak > start + 1_000) {
                    end = paragraphBreak;
                }
            }
            String pageText = textNormalizer.normalize(text.substring(start, end));
            if (!pageText.isBlank()) {
                pages.add(new ExtractedPage(page++, detectSectionTitle(pageText), pageText, Map.of("parser", parserName)));
            }
            start = Math.max(end, start + 1);
        }
        return pages;
    }

    private List<ExtractedPage> splitCodeIntoSections(String text, String extension) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<ExtractedPage> pages = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentTitle = "Code";
        int page = 1;
        for (String line : text.split("\\R", -1)) {
            String trimmed = line.trim();
            boolean boundary = isCodeBoundary(trimmed, extension);
            if (boundary && current.length() > 0) {
                page = flushCodeSection(pages, page, currentTitle, current, extension);
                currentTitle = codeTitle(trimmed);
            } else if (boundary && current.length() == 0) {
                currentTitle = codeTitle(trimmed);
            }

            if (current.length() + line.length() > TEXT_PAGE_SIZE && current.length() > 0) {
                page = flushCodeSection(pages, page, currentTitle, current, extension);
            }
            current.append(line).append('\n');
        }
        flushCodeSection(pages, page, currentTitle, current, extension);
        return pages;
    }

    private int flushCodeSection(List<ExtractedPage> pages, int page, String title, StringBuilder sectionText, String extension) {
        String text = textNormalizer.normalize(sectionText.toString());
        if (!text.isBlank()) {
            pages.add(new ExtractedPage(page++, title, text, Map.of(
                    "parser", "code-aware",
                    "language", codeLanguage(extension),
                    "sectionTitle", title
            )));
        }
        sectionText.setLength(0);
        return page;
    }

    private String formatCsvRow(List<String> headers, CSVRecord record, int rowIndex) {
        if (headers.isEmpty()) {
            List<String> values = new ArrayList<>();
            record.forEach(values::add);
            return "Row " + rowIndex + ": " + String.join(" | ", values);
        }
        List<String> fields = new ArrayList<>();
        for (String header : headers) {
            fields.add(header + ": " + record.get(header));
        }
        return "Row " + rowIndex + ": " + String.join(" | ", fields);
    }

    private boolean isHeading(XWPFParagraph paragraph, String text) {
        String style = paragraph.getStyle() == null ? "" : paragraph.getStyle().toLowerCase(Locale.ROOT);
        return style.contains("heading")
                || style.contains("title")
                || (text.length() <= 120 && text.equals(text.toUpperCase(Locale.ROOT)) && text.matches(".*[A-Z].*"));
    }

    private boolean isCodeBoundary(String line, String extension) {
        if (line == null || line.isBlank()) {
            return false;
        }
        if ("java".equals(extension)) {
            return line.startsWith("@")
                    || line.matches("(?i).*(class|interface|enum|record)\\s+[A-Za-z0-9_]+.*")
                    || line.matches("(?i).*(public|private|protected)\\s+.*\\([^;]*\\).*")
                    || line.matches("(?i).*(@Bean|@GetMapping|@PostMapping|@PutMapping|@DeleteMapping|@RequestMapping).*");
        }
        return line.matches("^[A-Za-z0-9_.-]+\\s*[:=].*")
                || line.startsWith("<bean")
                || line.startsWith("<property")
                || line.startsWith("{")
                || line.startsWith("\"");
    }

    private String codeTitle(String line) {
        String compact = line == null ? "" : line.replaceAll("\\s+", " ").trim();
        if (compact.isBlank()) {
            return "Code";
        }
        return compact.length() <= 160 ? compact : compact.substring(0, 157) + "...";
    }

    private String inferDocumentType(String sourceName, String sourceType, String mediaType) {
        String name = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        if (name.contains("resume") || name.contains("_cv") || name.endsWith("cv.pdf") || name.endsWith("cv.docx")) {
            return "resume";
        }
        if (name.endsWith(".java") || name.endsWith(".xml") || name.endsWith(".properties")
                || name.endsWith(".yml") || name.endsWith(".yaml") || name.endsWith(".json")) {
            return "code";
        }
        if ("url".equalsIgnoreCase(sourceType)) {
            return "web";
        }
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            return name.substring(dot + 1);
        }
        return mediaType == null || mediaType.isBlank() ? sourceType : mediaType;
    }

    private String inferLanguage(String sourceName, String documentType) {
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

    private String inferTopic(String filename) {
        return inferTopic(filename, List.of());
    }

    private String inferTopic(String sourceName, List<ExtractedPage> pages) {
        StringBuilder text = new StringBuilder(sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT));
        pages.stream()
                .map(ExtractedPage::sectionTitle)
                .filter(title -> title != null && !title.isBlank())
                .limit(5)
                .forEach(title -> text.append(' ').append(title.toLowerCase(Locale.ROOT)));
        String normalized = text.toString();
        if (normalized.contains("resume") || normalized.contains("curriculum vitae") || normalized.contains("education")
                || normalized.contains("experience") || normalized.contains("skills")) {
            return "resume";
        }
        if (normalized.contains("spring") || normalized.contains("boot") || normalized.contains("controller")
                || normalized.contains("repository") || normalized.contains("configuration")) {
            return "spring-boot";
        }
        if (normalized.contains("api") || normalized.contains("rest")) {
            return "api";
        }
        if (normalized.contains("java")) {
            return "java";
        }
        return "general";
    }

    private String codeLanguage(String extension) {
        return switch (extension) {
            case "yml", "yaml" -> "yaml";
            case "properties" -> "properties";
            default -> extension;
        };
    }

    private String codeContentType(String extension) {
        return switch (extension) {
            case "java" -> "text/x-java-source";
            case "xml" -> "application/xml";
            case "yml", "yaml" -> "application/x-yaml";
            case "properties" -> "text/x-java-properties";
            case "json" -> "application/json";
            default -> "text/plain";
        };
    }

    private String detectSectionTitle(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String firstLine = text.lines().findFirst().orElse("").trim();
        return firstLine.length() <= 120 ? firstLine : "";
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        if (file.getSize() > maxUploadBytes) {
            throw new IllegalArgumentException("File exceeds upload limit");
        }
    }

    private void validateBytes(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("File is required");
        }
        if (bytes.length > maxUploadBytes) {
            throw new IllegalArgumentException("File exceeds upload limit");
        }
    }

    private String sanitizeFilename(String filename) {
        return filename == null || filename.isBlank() ? "uploaded-file" : filename.replaceAll("[\\\\/]+", "_");
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
