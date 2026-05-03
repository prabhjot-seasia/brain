package com.assurant.brain.docs;

import com.lowagie.text.DocumentException;
import lombok.extern.log4j.Log4j2;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.HeadingAnchorExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.xhtmlrenderer.pdf.ITextRenderer;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.util.List;

@Log4j2
@Service
public class MarkdownPdfRenderer {

    private static final String CSS = """
            body { font-family: 'Helvetica', 'Arial', sans-serif; font-size: 11pt; line-height: 1.5;
                   color: #1a1a1a; margin: 0; }
            h1 { font-size: 22pt; color: #006ebb; border-bottom: 2pt solid #006ebb;
                 padding-bottom: 4pt; page-break-before: always; }
            h1.cover { page-break-before: avoid; border: none; }
            h2 { font-size: 16pt; color: #003a66; margin-top: 18pt; }
            h3 { font-size: 13pt; color: #1a1a1a; margin-top: 14pt; }
            h4 { font-size: 11pt; color: #555; }
            p  { margin: 6pt 0; }
            code { font-family: 'Courier New', monospace; font-size: 9.5pt;
                   background: #f4f4f4; padding: 1pt 3pt; }
            pre { background: #f4f4f4; padding: 8pt; font-size: 9pt;
                  white-space: pre-wrap; word-wrap: break-word; }
            table { border-collapse: collapse; width: 100%; margin: 8pt 0; }
            th, td { border: 1pt solid #ddd; padding: 4pt 6pt; text-align: left; font-size: 10pt; }
            th { background: #f0f4f8; }
            .mermaid-svg svg { max-width: 100%; height: auto; }
            .mermaid-png img { max-width: 100%; height: auto; }
            .mermaid-png { text-align: center; margin: 8pt 0; page-break-inside: avoid; }
            .cover-page { text-align: center; padding-top: 80pt; page-break-after: always; }
            .cover-page h1 { font-size: 32pt; border: none; }
            .cover-page .meta { font-size: 12pt; color: #555; margin-top: 30pt; line-height: 2; }
            @page { size: A4; margin: 18mm 16mm 22mm 16mm;
                    @bottom-center { content: "Page " counter(page) " of " counter(pages);
                                     font-size: 9pt; color: #888; } }
            """;

    public byte[] render(String html, String title) throws IOException {
        String wrapped = """
                <html xmlns="http://www.w3.org/1999/xhtml"><head><meta charset="UTF-8"/><title>%s</title>
                <style>%s</style></head><body>%s</body></html>
                """.formatted(escape(title), CSS, html);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document doc = parseHardened(wrapped);
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocument(doc, null);
            renderer.layout();
            renderer.createPDF(out);
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IOException("Flying Saucer PDF render failed: " + e.getMessage(), e);
        }
    }

    private Document parseHardened(String xhtml) throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(new InputSource(new StringReader(xhtml)));
        } catch (ParserConfigurationException | org.xml.sax.SAXException e) {
            throw new IOException("XHTML parse failed: " + e.getMessage(), e);
        }
    }

    public String markdownToHtml(String markdown) {
        if (markdown == null) return "";
        List<Extension> extensions = List.of(
                TablesExtension.create(),
                HeadingAnchorExtension.create());
        Parser parser = Parser.builder().extensions(extensions).build();
        HtmlRenderer renderer = HtmlRenderer.builder()
                .extensions(extensions)
                .escapeHtml(true)
                .sanitizeUrls(true)
                .build();
        return renderer.render(parser.parse(markdown));
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
