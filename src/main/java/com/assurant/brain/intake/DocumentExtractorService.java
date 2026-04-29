package com.assurant.brain.intake;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.stream.Collectors;

@Log4j2
@Service
@RequiredArgsConstructor
public class DocumentExtractorService {

    private final ImageAnalyzerService imageAnalyzerService;

    public String extract(MultipartFile file) {
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";

        if (filename.endsWith(".pdf") || contentType.contains("pdf")) {
            return extractPdf(file);
        }
        if (filename.endsWith(".docx") || contentType.contains("wordprocessingml")) {
            return extractDocx(file);
        }
        if (contentType.startsWith("image/") || filename.endsWith(".png") || filename.endsWith(".jpg") || filename.endsWith(".jpeg")) {
            return imageAnalyzerService.analyzeImage(file);
        }
        if (filename.endsWith(".txt") || filename.endsWith(".md") || contentType.startsWith("text/")) {
            return extractPlainText(file);
        }

        throw new IllegalArgumentException("Unsupported file type: " + contentType + " (" + filename + ")");
    }

    private String extractPdf(MultipartFile file) {
        try (InputStream is = file.getInputStream(); PDDocument doc = Loader.loadPDF(is.readAllBytes())) {
            return new PDFTextStripper().getText(doc).trim();
        } catch (Exception e) {
            log.error("Failed to extract PDF content from {}", file.getOriginalFilename(), e);
            throw new IllegalStateException("PDF extraction failed: " + e.getMessage());
        }
    }

    private String extractDocx(MultipartFile file) {
        try (InputStream is = file.getInputStream(); XWPFDocument doc = new XWPFDocument(is)) {
            return doc.getParagraphs().stream()
                    .map(XWPFParagraph::getText)
                    .filter(text -> text != null && !text.isBlank())
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.error("Failed to extract DOCX content from {}", file.getOriginalFilename(), e);
            throw new IllegalStateException("DOCX extraction failed: " + e.getMessage());
        }
    }

    private String extractPlainText(MultipartFile file) {
        try {
            return new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            throw new IllegalStateException("Text extraction failed: " + e.getMessage());
        }
    }
}
