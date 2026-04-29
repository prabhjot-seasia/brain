package com.assurant.brain.intake;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("DocumentExtractorService")
class DocumentExtractorServiceTest {

    private ImageAnalyzerService imageAnalyzerService;
    private DocumentExtractorService service;

    @BeforeEach
    void setup() {
        imageAnalyzerService = mock(ImageAnalyzerService.class);
        service = new DocumentExtractorService(imageAnalyzerService);
    }

    @Test
    @DisplayName("extracts text from plain text file")
    void extractsPlainText() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain",
                "Add rate limiting to the API".getBytes(StandardCharsets.UTF_8));

        String result = service.extract(file);

        assertThat(result).isEqualTo("Add rate limiting to the API");
    }

    @Test
    @DisplayName("extracts text from markdown file")
    void extractsMarkdown() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "requirements.md", "text/markdown",
                "# Feature\n- Item 1\n- Item 2".getBytes(StandardCharsets.UTF_8));

        String result = service.extract(file);

        assertThat(result).contains("Feature");
        assertThat(result).contains("Item 1");
    }

    @Test
    @DisplayName("delegates image files to ImageAnalyzerService")
    void delegatesImages() {
        when(imageAnalyzerService.analyzeImage(any()))
                .thenReturn("## Extracted Requirements\n- Build login page");

        MockMultipartFile file = new MockMultipartFile(
                "file", "whiteboard.png", "image/png", new byte[]{1, 2, 3});

        String result = service.extract(file);

        assertThat(result).contains("Build login page");
    }

    @Test
    @DisplayName("throws on unsupported file type")
    void throwsOnUnsupported() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "data.zip", "application/zip",
                new byte[]{0x50, 0x4B, 0x03, 0x04});

        assertThatThrownBy(() -> service.extract(file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    @DisplayName("detects PDF by content type")
    void detectsPdfByContentType() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "doc.pdf", "application/pdf",
                "%PDF-1.4 fake pdf content".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.extract(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PDF extraction failed");
    }

    @Test
    @DisplayName("detects DOCX by filename extension")
    void detectsDocxByExtension() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "spec.docx", "application/octet-stream",
                "not a real docx".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.extract(file))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DOCX extraction failed");
    }
}
