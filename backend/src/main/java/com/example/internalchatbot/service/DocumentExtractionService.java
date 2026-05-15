package com.example.internalchatbot.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.Collectors;

@Service
public class DocumentExtractionService {

    private final long maxUploadBytes;

    public DocumentExtractionService(@Value("${security.max-upload-bytes}") long maxUploadBytes) {
        this.maxUploadBytes = maxUploadBytes;
    }

    public String extractText(MultipartFile file) {
        validate(file);
        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            if (filename.endsWith(".pdf")) {
                return extractPdf(file);
            }
            if (filename.endsWith(".docx")) {
                return extractDocx(file);
            }
            if (filename.endsWith(".txt") || filename.endsWith(".log")) {
                return new String(file.getBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("Unable to extract document text", ex);
        }
        throw new IllegalArgumentException("Only PDF, DOCX, TXT, and LOG files are supported");
    }

    private void validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }
        if (file.getSize() > maxUploadBytes) {
            throw new IllegalArgumentException("File exceeds upload limit");
        }
    }

    private String extractPdf(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(MultipartFile file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(file.getInputStream())) {
            return document.getParagraphs()
                    .stream()
                    .map(XWPFParagraph::getText)
                    .collect(Collectors.joining("\n"));
        }
    }
}
