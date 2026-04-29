package com.assurant.brain.docs;

import com.assurant.brain.config.properties.BrainProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@Service
@RequiredArgsConstructor
public class MermaidPreRenderer {

    private static final Pattern MERMAID_FENCE = Pattern.compile(
            "(?s)```mermaid\\s*\\n(.*?)\\n```");
    private static final long DEFAULT_MMDC_TIMEOUT_SECONDS = 30;
    private static final String DEFAULT_MMDC = "mmdc";

    private final BrainProperties brainProperties;

    public record PreRenderResult(String markdown, java.util.Map<String, String> placeholderToHtml) {}

    public PreRenderResult preRender(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return new PreRenderResult(markdown == null ? "" : markdown, java.util.Map.of());
        }
        Matcher m = MERMAID_FENCE.matcher(markdown);
        if (!m.find()) return new PreRenderResult(markdown, java.util.Map.of());

        java.util.Map<String, String> placeholders = new java.util.LinkedHashMap<>();
        StringBuilder sb = new StringBuilder();
        int last = 0;
        m.reset();
        int idx = 0;
        while (m.find()) {
            sb.append(markdown, last, m.start());
            String mermaidSource = m.group(1);
            String svg = renderToSvg(mermaidSource, idx);
            if (svg == null) {
                sb.append(m.group());
            } else {
                String token = "MERMAIDPLACEHOLDER" + idx + "TOKEN";
                placeholders.put(token, "<div class=\"mermaid-svg\">\n" + svg + "\n</div>");
                sb.append("\n\n").append(token).append("\n\n");
            }
            idx++;
            last = m.end();
        }
        sb.append(markdown, last, markdown.length());
        return new PreRenderResult(sb.toString(), placeholders);
    }

    private String renderToSvg(String mermaidSource, int index) {
        Path tempIn = null;
        Path tempOut = null;
        try {
            java.nio.file.attribute.FileAttribute<?> ownerOnly = isPosix()
                    ? java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))
                    : null;
            tempIn = ownerOnly == null
                    ? Files.createTempFile("mermaid-" + index + "-", ".mmd")
                    : Files.createTempFile("mermaid-" + index + "-", ".mmd", ownerOnly);
            tempOut = ownerOnly == null
                    ? Files.createTempFile("mermaid-" + index + "-", ".svg")
                    : Files.createTempFile("mermaid-" + index + "-", ".svg", ownerOnly);
            Files.writeString(tempIn, mermaidSource, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(mmdcCommand(), "-i", tempIn.toString(),
                    "-o", tempOut.toString(), "-b", "transparent");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            java.util.concurrent.CompletableFuture<Void> drain =
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try (var in = p.getInputStream()) {
                            byte[] buf = new byte[4096];
                            while (in.read(buf) >= 0) { /* drain to prevent pipe deadlock */ }
                        } catch (IOException ignored) {
                            // process closed; nothing to do
                        }
                    });
            boolean finished = p.waitFor(mmdcTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                drain.cancel(true);
                log.debug("mermaid-cli timed out for diagram #{}", index);
                return null;
            }
            drain.join();
            if (p.exitValue() != 0) {
                log.debug("mermaid-cli exit={} for diagram #{} — falling back to fenced source",
                        p.exitValue(), index);
                return null;
            }
            return Files.readString(tempOut, StandardCharsets.UTF_8);
        } catch (IOException | InterruptedException | RuntimeException e) {
            log.debug("Mermaid render failed for diagram #{}: {} — falling back to fenced source",
                    index, e.getMessage());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        } finally {
            tryDelete(tempIn);
            tryDelete(tempOut);
        }
    }

    private boolean isPosix() {
        return java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
    }

    private long mmdcTimeoutSeconds() {
        if (brainProperties.docs() == null || brainProperties.docs().mermaidTimeoutSeconds() <= 0) {
            return DEFAULT_MMDC_TIMEOUT_SECONDS;
        }
        return brainProperties.docs().mermaidTimeoutSeconds();
    }

    private String mmdcCommand() {
        if (brainProperties.docs() == null
                || brainProperties.docs().mermaidCliPath() == null
                || brainProperties.docs().mermaidCliPath().isBlank()) {
            return DEFAULT_MMDC;
        }
        return brainProperties.docs().mermaidCliPath();
    }

    private void tryDelete(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.debug("Mermaid temp cleanup failed for {}: {}", p, e.getMessage());
        }
    }
}
