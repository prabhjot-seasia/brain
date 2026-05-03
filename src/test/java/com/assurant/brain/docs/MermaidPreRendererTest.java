package com.assurant.brain.docs;

import com.assurant.brain.config.properties.BrainProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MermaidPreRenderer")
class MermaidPreRendererTest {

    private MermaidPreRenderer renderer;

    @BeforeEach
    void setup() {
        var docs = new BrainProperties.Docs("/nonexistent/mmdc-not-installed", 60, 0, 0, 5);
        var props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, docs, null);
        renderer = new MermaidPreRenderer(props);
    }

    @Test
    @DisplayName("returns input unchanged when no mermaid fenced blocks present")
    void noFencesUnchanged() {
        String md = "# Heading\n\nJust text and `code` and a [link](http://x).";
        var res = renderer.preRender(md);
        assertThat(res.markdown()).isEqualTo(md);
        assertThat(res.placeholderToHtml()).isEmpty();
    }

    @Test
    @DisplayName("returns input unchanged when input is null or blank")
    void blankInputUnchanged() {
        assertThat(renderer.preRender(null).markdown()).isEqualTo("");
        assertThat(renderer.preRender("").markdown()).isEmpty();
    }

    @Test
    @DisplayName("falls back to fenced source when mmdc binary is unavailable")
    void mmdcUnavailableFallsBack() {
        String md = "Before\n\n```mermaid\ngraph TD; A-->B;\n```\n\nAfter";
        var res = renderer.preRender(md);
        assertThat(res.markdown()).contains("```mermaid").contains("graph TD; A-->B;");
        assertThat(res.markdown()).doesNotContain("<svg");
        assertThat(res.placeholderToHtml()).isEmpty();
    }

    @Test
    @DisplayName("multiple mermaid fences each fall back independently to original source")
    void multipleFencesFallBackIndependently() {
        String md = "```mermaid\ngraph TD; A-->B;\n```\n\nMid\n\n```mermaid\nsequenceDiagram\nA->>B: hi\n```";
        var res = renderer.preRender(md);
        assertThat(res.markdown()).contains("graph TD; A-->B;").contains("sequenceDiagram");
        assertThat(res.placeholderToHtml()).isEmpty();
    }

    @Test
    @DisplayName("non-mermaid fenced code blocks are left untouched")
    void otherCodeFencesIgnored() {
        String md = "```java\nclass Foo {}\n```\n\n```\nplain code\n```";
        var res = renderer.preRender(md);
        assertThat(res.markdown()).isEqualTo(md);
        assertThat(res.placeholderToHtml()).isEmpty();
    }
}
