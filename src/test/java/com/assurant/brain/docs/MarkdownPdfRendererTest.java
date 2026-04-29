package com.assurant.brain.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("MarkdownPdfRenderer")
class MarkdownPdfRendererTest {

    private final MarkdownPdfRenderer renderer = new MarkdownPdfRenderer();

    @Test
    @DisplayName("markdownToHtml produces table HTML for GFM table")
    void tableExtensionWorks() {
        String md = "| col1 | col2 |\n|---|---|\n| a | b |";
        String html = renderer.markdownToHtml(md);
        assertThat(html).contains("<table>").contains("<th>col1</th>").contains("<td>a</td>");
    }

    @Test
    @DisplayName("markdownToHtml renders headings, lists, and code fences")
    void basicMarkdown() {
        String md = "# Title\n\n- one\n- two\n\n```\ncode\n```";
        String html = renderer.markdownToHtml(md);
        assertThat(html).contains("<h1").contains("Title")
                .contains("<ul>").contains("<li>one</li>")
                .contains("<pre><code>");
    }

    @Test
    @DisplayName("render produces non-empty PDF bytes starting with %PDF magic header")
    void pdfBytesValid() throws IOException {
        String html = "<h1>Project</h1><p>Hello world</p>";
        byte[] bytes = renderer.render(html, "Project");
        assertThat(bytes).isNotEmpty();
        assertThat(bytes[0]).isEqualTo((byte) '%');
        assertThat(bytes[1]).isEqualTo((byte) 'P');
        assertThat(bytes[2]).isEqualTo((byte) 'D');
        assertThat(bytes[3]).isEqualTo((byte) 'F');
    }

    @Test
    @DisplayName("markdownToHtml with null input returns empty string")
    void nullInputSafe() {
        assertThat(renderer.markdownToHtml(null)).isEmpty();
    }

    @Test
    @DisplayName("markdownToHtml escapes raw HTML so LLM-supplied <script> can't smuggle XSS into PDF")
    void rawHtmlIsEscaped() {
        String md = "Hello <script>alert(1)</script> world";
        String html = renderer.markdownToHtml(md);
        assertThat(html).doesNotContain("<script>").contains("&lt;script&gt;");
    }

    @Test
    @DisplayName("markdownToHtml sanitizes javascript: URLs")
    void javascriptUrlsSanitized() {
        String md = "[click](javascript:alert(1))";
        String html = renderer.markdownToHtml(md);
        assertThat(html).doesNotContain("javascript:alert");
    }

    @Test
    @DisplayName("render rejects XHTML containing external entities (XXE-hardened)")
    void xxeBlocked() {
        String malicious = "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]><h1>&xxe;</h1>";
        assertThatThrownBy(() -> renderer.render(malicious, "Hostile"))
                .isInstanceOf(IOException.class);
    }
}
