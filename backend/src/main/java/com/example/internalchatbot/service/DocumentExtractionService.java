package com.example.internalchatbot.service;

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
        String filename = sanitizeFilename(file.getOriginalFilename());
        String extension = extension(filename);

        try {
            byte[] bytes = file.getBytes();
            return switch (extension) {
                case "pdf" -> extractPdf(filename, file.getContentType(), bytes);
                case "docx" -> extractDocx(filename, file.getContentType(), bytes);
                case "csv" -> extractCsv(filename, file.getContentType(), bytes);
                case "txt", "log" -> extractText(filename, file.getContentType(), bytes, extension);
                default -> extractWithTika(filename, file.getContentType(), bytes, "tika-fallback");
            };
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to read uploaded document", ex);
        }
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
        return new ExtractedDocument(
                sourceName,
                sourceType,
                sourceUrl,
                mediaType == null ? "application/octet-stream" : mediaType,
                parserName,
                ContentHash.sha256(textNormalizer.compact(combinedText)),
                metadata,
                cleanedPages
        );
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

    private String sanitizeFilename(String filename) {
        return filename == null || filename.isBlank() ? "uploaded-file" : filename.replaceAll("[\\\\/]+", "_");
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
