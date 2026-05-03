package com.assurant.brain.confluence;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConfluenceStorageRenderer")
class ConfluenceStorageRendererTest {

    private final ConfluenceStorageRenderer renderer = new ConfluenceStorageRenderer();

    @Test
    @DisplayName("plain markdown becomes Confluence storage HTML with no attachments")
    void plainMarkdown() {
        ConfluenceStorageRenderer.RenderedPage page = renderer.render("# Title\n\nHello world");
        assertThat(page.storageBody()).contains("<h1>Title</h1>");
        assertThat(page.storageBody()).contains("Hello world");
        assertThat(page.attachments()).isEmpty();
    }

    @Test
    @DisplayName("data-URI image is extracted as attachment + replaced with ac:image")
    void dataUriImageBecomesAttachment() {
        String md = "Here is a diagram:\n\n<img src=\"data:image/png;base64,aGVsbG8=\"/>\n";
        ConfluenceStorageRenderer.RenderedPage page = renderer.render(md);

        assertThat(page.attachments()).hasSize(1);
        assertThat(page.attachments().keySet()).containsExactly("diagram-0.png");
        assertThat(page.storageBody()).contains("<ac:image>");
        assertThat(page.storageBody()).contains("<ri:attachment ri:filename=\"diagram-0.png\"");
        assertThat(page.storageBody()).doesNotContain("data:image/png;base64");
    }

    @Test
    @DisplayName("fenced code block is wrapped in ac:structured-macro 'code'")
    void codeBlockBecomesMacro() {
        String md = "```java\npublic class X {}\n```";
        ConfluenceStorageRenderer.RenderedPage page = renderer.render(md);
        assertThat(page.storageBody()).contains("<ac:structured-macro ac:name=\"code\">");
        assertThat(page.storageBody()).contains("<ac:parameter ac:name=\"language\">java</ac:parameter>");
        assertThat(page.storageBody()).contains("<![CDATA[public class X {}");
    }

    @Test
    @DisplayName("table from gfm extension renders to HTML table")
    void tableRenders() {
        String md = "| A | B |\n|---|---|\n| 1 | 2 |\n";
        String body = renderer.render(md).storageBody();
        assertThat(body).contains("<table>");
        assertThat(body).contains("<th>A</th>").contains("<td>1</td>");
    }
}
