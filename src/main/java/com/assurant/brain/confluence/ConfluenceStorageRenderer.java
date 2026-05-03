package com.assurant.brain.confluence;

import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ConfluenceStorageRenderer {

    private static final Pattern DATA_URI_IMG = Pattern.compile(
            "(?s)<img\\s+src=\"data:image/(png|jpeg|gif);base64,([^\"]+)\"[^>]*/?>");
    private static final Pattern CODE_BLOCK = Pattern.compile(
            "(?s)<pre><code(?: class=\"language-([^\"]+)\")?>(.*?)</code></pre>");

    private final Parser parser;
    private final HtmlRenderer renderer;

    public ConfluenceStorageRenderer() {
        List<? extends org.commonmark.Extension> extensions = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(extensions).build();
        this.renderer = HtmlRenderer.builder().extensions(extensions).build();
    }

    public RenderedPage render(String markdown) {
        Map<String, byte[]> attachments = new LinkedHashMap<>();
        String html = renderer.render(parser.parse(markdown == null ? "" : markdown));
        String storage = html;

        Matcher imgMatcher = DATA_URI_IMG.matcher(storage);
        StringBuilder sb = new StringBuilder();
        int diagramIdx = 0;
        while (imgMatcher.find()) {
            String mime = imgMatcher.group(1);
            byte[] bytes = Base64.getDecoder().decode(imgMatcher.group(2));
            String filename = "diagram-" + (diagramIdx++) + "." + mime;
            attachments.put(filename, bytes);
            String replacement = Matcher.quoteReplacement(
                    "<ac:image><ri:attachment ri:filename=\"" + filename + "\"/></ac:image>");
            imgMatcher.appendReplacement(sb, replacement);
        }
        imgMatcher.appendTail(sb);
        storage = sb.toString();

        Matcher codeMatcher = CODE_BLOCK.matcher(storage);
        StringBuilder sb2 = new StringBuilder();
        while (codeMatcher.find()) {
            String language = codeMatcher.group(1) == null ? "java" : codeMatcher.group(1);
            String body = decodeEntities(codeMatcher.group(2));
            String replacement = Matcher.quoteReplacement(
                    "<ac:structured-macro ac:name=\"code\">"
                            + "<ac:parameter ac:name=\"language\">" + language + "</ac:parameter>"
                            + "<ac:plain-text-body><![CDATA[" + body + "]]></ac:plain-text-body>"
                            + "</ac:structured-macro>");
            codeMatcher.appendReplacement(sb2, replacement);
        }
        codeMatcher.appendTail(sb2);
        storage = sb2.toString();

        return new RenderedPage(storage, attachments);
    }

    private String decodeEntities(String s) {
        if (s == null) return "";
        return s.replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&amp;", "&");
    }

    public record RenderedPage(String storageBody, Map<String, byte[]> attachments) {}
}
