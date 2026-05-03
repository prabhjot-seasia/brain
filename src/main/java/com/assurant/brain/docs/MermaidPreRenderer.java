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
    private static final String DEFAULT_RENDER_WIDTH = "1400";
    private static final String DEFAULT_RENDER_SCALE = "2";

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
        boolean svgMode = "svg".equalsIgnoreCase(outputFormat());
        while (m.find()) {
            sb.append(markdown, last, m.start());
            String mermaidSource = m.group(1);
            String embed = svgMode ? renderToSvgEmbed(mermaidSource, idx)
                                   : renderToPngEmbed(mermaidSource, idx);
            if (embed == null) {
                sb.append(m.group());
            } else {
                String token = "MERMAIDPLACEHOLDER" + idx + "TOKEN";
                placeholders.put(token, embed);
                sb.append("\n\n").append(token).append("\n\n");
            }
            idx++;
            last = m.end();
        }
        sb.append(markdown, last, markdown.length());
        return new PreRenderResult(sb.toString(), placeholders);
    }

    private String renderToPngEmbed(String mermaidSource, int index) {
        byte[] png = runMmdc(mermaidSource, index, ".png", true);
        if (png == null) return null;
        String base64 = java.util.Base64.getEncoder().encodeToString(png);
        return "<div class=\"mermaid-png\"><img src=\"data:image/png;base64," + base64
                + "\" alt=\"diagram-" + index + "\" style=\"max-width:100%;height:auto;\"/></div>";
    }

    private String renderToSvgEmbed(String mermaidSource, int index) {
        byte[] svg = runMmdc(mermaidSource, index, ".svg", false);
        if (svg == null) return null;
        return "<div class=\"mermaid-svg\">\n"
                + new String(svg, StandardCharsets.UTF_8) + "\n</div>";
    }

    private byte[] runMmdc(String mermaidSource, int index, String suffix, boolean png) {
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
                    ? Files.createTempFile("mermaid-" + index + "-", suffix)
                    : Files.createTempFile("mermaid-" + index + "-", suffix, ownerOnly);
            Files.writeString(tempIn, mermaidSource, StandardCharsets.UTF_8);

            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(mmdcCommand());
            cmd.add("-i"); cmd.add(tempIn.toString());
            cmd.add("-o"); cmd.add(tempOut.toString());
            if (png) {
                cmd.add("-b"); cmd.add("white");
                cmd.add("--width"); cmd.add(renderWidth());
                cmd.add("--scale"); cmd.add(renderScale());
            } else {
                cmd.add("-b"); cmd.add("transparent");
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
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
            return Files.readAllBytes(tempOut);
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

    private String outputFormat() {
        if (brainProperties.docs() == null
                || brainProperties.docs().mermaidOutputFormat() == null
                || brainProperties.docs().mermaidOutputFormat().isBlank()) {
            return "png";
        }
        return brainProperties.docs().mermaidOutputFormat();
    }

    private String renderWidth() {
        if (brainProperties.docs() == null || brainProperties.docs().mermaidRenderWidth() <= 0) {
            return DEFAULT_RENDER_WIDTH;
        }
        return String.valueOf(brainProperties.docs().mermaidRenderWidth());
    }

    private String renderScale() {
        if (brainProperties.docs() == null || brainProperties.docs().mermaidRenderScale() <= 0) {
            return DEFAULT_RENDER_SCALE;
        }
        return String.valueOf(brainProperties.docs().mermaidRenderScale());
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
