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
    private static final String DEFAULT_RENDER_WIDTH = "900";
    private static final String DEFAULT_RENDER_SCALE = "2";

    private final BrainProperties brainProperties;
    private volatile java.util.concurrent.Semaphore renderPermits;

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
            String mermaidSource = sanitizeMermaid(m.group(1), idx);
            if (mermaidSource == null || mermaidSource.isBlank()) {
                log.warn("Skipping empty mermaid block #{} — keeping fenced source", idx);
                sb.append(m.group());
                idx++;
                last = m.end();
                continue;
            }
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
        java.util.concurrent.Semaphore permits = permits();
        try {
            permits.acquire();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
        try {
            int attempts = mermaidRetryAttempts();
            for (int attempt = 1; attempt <= attempts; attempt++) {
                RenderOutcome outcome = runMmdcLocked(mermaidSource, index, suffix, png);
                if (outcome.bytes() != null) {
                    if (attempt > 1) {
                        log.info("mermaid-cli succeeded on attempt {}/{} for diagram #{}", attempt, attempts, index);
                    }
                    return outcome.bytes();
                }
                if (outcome.parserError()) {
                    String raw = mermaidSource == null ? "" : mermaidSource;
                    String trimmed = raw.length() > 800 ? raw.substring(0, 800) + "…(truncated)" : raw;
                    String inline = trimmed.replace("\n", " ↵ ").replace("\r", "");
                    log.warn("mermaid bad-source [diagram #{}, length={}]: {}",
                            index, raw.length(), inline);
                    return null;
                }
                if (attempt < attempts) {
                    long backoffMs = 500L * attempt;
                    log.info("mermaid-cli attempt {}/{} failed for diagram #{} — retrying in {}ms",
                            attempt, attempts, index, backoffMs);
                    try { Thread.sleep(backoffMs); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return null; }
                }
            }
            return null;
        } finally {
            permits.release();
        }
    }

    private record RenderOutcome(byte[] bytes, boolean parserError) {
        static RenderOutcome ok(byte[] b) { return new RenderOutcome(b, false); }
        static RenderOutcome transientFail() { return new RenderOutcome(null, false); }
        static RenderOutcome parserFail() { return new RenderOutcome(null, true); }
    }

    private int mermaidRetryAttempts() {
        String env = System.getenv("BRAIN_DOCS_MERMAID_RETRY_ATTEMPTS");
        if (env == null || env.isBlank()) return 3;
        try { return Math.max(1, Integer.parseInt(env)); }
        catch (NumberFormatException nfe) { return 3; }
    }

    private java.util.concurrent.Semaphore permits() {
        java.util.concurrent.Semaphore p = renderPermits;
        if (p != null) return p;
        synchronized (this) {
            if (renderPermits == null) {
                int max = mermaidMaxParallel();
                renderPermits = new java.util.concurrent.Semaphore(max, true);
                log.info("MermaidPreRenderer using mermaidMaxParallel={}", max);
            }
            return renderPermits;
        }
    }

    private boolean mermaidDebug() {
        return "true".equalsIgnoreCase(System.getenv("BRAIN_DOCS_MERMAID_DEBUG"));
    }

    private static final Pattern BAD_LABELED_ARROW = Pattern.compile("(-->|==>|-\\.->)\\|([^|]+)\\|>");
    private static final Pattern ANGLE_BRACKET_ID = Pattern.compile("(\\w+)<(\\d+)>");
    private static final Pattern C4_COMPONENT_DECL = Pattern.compile(
            "(?m)^(\\s*)component\\s+\"([^\"]+)\"\\s+as\\s+(\\w+)\\s*$");
    private static final Pattern C4_NODE_DECL = Pattern.compile(
            "(?m)^(\\s*)node\\s+\"([^\"]+)\"\\s*$");
    private static final Pattern FIRST_NONBLANK_LINE = Pattern.compile("(?s)^\\s*([^\\n]+)");
    private static final Pattern PARTICIPANT_QUOTED_ALIAS = Pattern.compile(
            "(?m)^(\\s*participant\\s+\\w+)\\s+as\\s+\"[^\"]*\"\\s*$");
    private static final Pattern CLASS_REL_DOUBLE_AGG = Pattern.compile("\\*-->\\*");
    private static final Pattern CLASS_REL_DOTTED_QUOTED = Pattern.compile(
            "(\\w+)\\s+\"[^\"]*\\.\\.[^\"]*\"\\s+(\\*--|\\*-->|--)");

    private String sanitizeMermaid(String source, int index) {
        if (source == null) return null;
        String original = source;
        String fixed = BAD_LABELED_ARROW.matcher(source).replaceAll("$1|$2|");
        boolean arrowFixed = !fixed.equals(original);

        boolean isFlowchart = isFlowchartLike(fixed);
        boolean c4Fixed = false;
        if (isFlowchart) {
            String before = fixed;
            fixed = C4_COMPONENT_DECL.matcher(fixed).replaceAll("$1$3[$2]");
            fixed = C4_NODE_DECL.matcher(fixed).replaceAll(matchResult -> {
                String indent = matchResult.group(1);
                String label = matchResult.group(2);
                String id = label.replaceAll("[^A-Za-z0-9]", "_");
                return indent + id + "[" + label + "]";
            });
            c4Fixed = !fixed.equals(before);
        }

        String beforeAngle = fixed;
        fixed = ANGLE_BRACKET_ID.matcher(fixed).replaceAll("$1_$2");
        boolean angleFixed = !fixed.equals(beforeAngle);

        String beforeSeq = fixed;
        fixed = PARTICIPANT_QUOTED_ALIAS.matcher(fixed).replaceAll("$1");
        boolean seqFixed = !fixed.equals(beforeSeq);

        boolean isClassDiagram = isClassDiagramLike(fixed);
        boolean classFixed = false;
        if (isClassDiagram) {
            String beforeClass = fixed;
            fixed = CLASS_REL_DOUBLE_AGG.matcher(fixed).replaceAll("-->");
            fixed = CLASS_REL_DOTTED_QUOTED.matcher(fixed).replaceAll("$1 ..>");
            classFixed = !fixed.equals(beforeClass);
        }

        if (arrowFixed) {
            log.info("mermaid sanitized diagram #{}: replaced bad '-->|label|>' with '-->|label|'", index);
        }
        if (c4Fixed) {
            log.info("mermaid sanitized diagram #{}: converted C4 'component'/'node' declarations to flowchart syntax", index);
        }
        if (angleFixed) {
            log.info("mermaid sanitized diagram #{}: rewrote 'node<NN>' identifiers to 'node_NN'", index);
        }
        if (seqFixed) {
            log.info("mermaid sanitized diagram #{}: stripped quoted aliases from sequenceDiagram participants", index);
        }
        if (classFixed) {
            log.info("mermaid sanitized diagram #{}: normalised classDiagram relationships ('*-->*' -> '-->', dotted-quoted cardinality -> '..>')", index);
        }
        return fixed;
    }

    private boolean isFlowchartLike(String source) {
        java.util.regex.Matcher m = FIRST_NONBLANK_LINE.matcher(source);
        if (!m.find()) return false;
        String firstLine = m.group(1).trim().toLowerCase(java.util.Locale.ROOT);
        return firstLine.startsWith("graph ") || firstLine.startsWith("flowchart ");
    }

    private boolean isClassDiagramLike(String source) {
        java.util.regex.Matcher m = FIRST_NONBLANK_LINE.matcher(source);
        if (!m.find()) return false;
        return m.group(1).trim().toLowerCase(java.util.Locale.ROOT).startsWith("classdiagram");
    }

    private String puppeteerConfigPath() {
        String env = System.getenv("BRAIN_DOCS_PUPPETEER_CONFIG_PATH");
        return env == null ? "" : env;
    }

    private int mermaidMaxParallel() {
        if (brainProperties.docs() == null || brainProperties.docs().mermaidMaxParallel() <= 0) return 1;
        return brainProperties.docs().mermaidMaxParallel();
    }

    private RenderOutcome runMmdcLocked(String mermaidSource, int index, String suffix, boolean png) {
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
            String puppeteerCfg = puppeteerConfigPath();
            if (puppeteerCfg != null && !puppeteerCfg.isBlank()) {
                cmd.add("-p"); cmd.add(puppeteerCfg);
            }
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
            pb.directory(tempIn.getParent().toFile());
            java.util.Map<String, String> env = pb.environment();
            env.computeIfAbsent("HOME", k -> System.getProperty("java.io.tmpdir"));
            if (mermaidDebug()) {
                env.put("DEBUG", "puppeteer:browsers:launcher");
            }
            Process p = pb.start();
            java.util.concurrent.CompletableFuture<String> stdoutDrain = drainAsync(p.getInputStream());
            java.util.concurrent.CompletableFuture<String> stderrDrain = drainAsync(p.getErrorStream());
            boolean finished = p.waitFor(mmdcTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                stdoutDrain.cancel(true);
                stderrDrain.cancel(true);
                log.warn("mermaid-cli timed out for diagram #{} after {}s", index, mmdcTimeoutSeconds());
                return RenderOutcome.transientFail();
            }
            String stdoutSnippet = stdoutDrain.join();
            String stderrSnippet = stderrDrain.join();
            if (p.exitValue() != 0) {
                boolean parserError = stderrSnippet != null
                        && (stderrSnippet.contains("Parser.parseError")
                                || stderrSnippet.contains("Lexical error")
                                || stderrSnippet.contains("Syntax error"));
                log.warn("mermaid-cli exit={} for diagram #{}: stderr={} stdout={} — falling back to fenced source",
                        p.exitValue(), index, stderrSnippet, stdoutSnippet);
                return parserError ? RenderOutcome.parserFail() : RenderOutcome.transientFail();
            }
            log.debug("mermaid-cli ok for diagram #{}", index);
            return RenderOutcome.ok(Files.readAllBytes(tempOut));
        } catch (IOException | InterruptedException | RuntimeException e) {
            log.warn("Mermaid render failed for diagram #{}: {} — falling back to fenced source",
                    index, e.toString());
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return RenderOutcome.transientFail();
        } finally {
            tryDelete(tempIn);
            tryDelete(tempOut);
        }
    }

    private static final int DRAIN_LIMIT_BYTES = 16384;

    private java.util.concurrent.CompletableFuture<String> drainAsync(java.io.InputStream stream) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            boolean truncated = false;
            try (var in = stream) {
                byte[] chunk = new byte[1024];
                int n;
                while ((n = in.read(chunk)) >= 0) {
                    int remaining = DRAIN_LIMIT_BYTES - buf.size();
                    if (remaining <= 0) { truncated = true; continue; }
                    buf.write(chunk, 0, Math.min(n, remaining));
                    if (buf.size() >= DRAIN_LIMIT_BYTES) truncated = true;
                }
            } catch (IOException ignored) {
                // process closed
            }
            String s = buf.toString(StandardCharsets.UTF_8).strip();
            return truncated ? s + "…(truncated)" : s;
        });
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
