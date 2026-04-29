package com.assurant.brain.sandbox;

import com.assurant.brain.config.properties.BrainProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Log4j2
@Service
@RequiredArgsConstructor
public class SandboxValidationService {

    public enum BuildTool { GRADLE, MAVEN, NPM, UNKNOWN }

    public record ValidationResult(boolean compiled, boolean testsPassed, String stdout, String stderr,
                                    int exitCode, BuildTool buildTool) {

        public boolean ok() { return compiled && testsPassed && exitCode == 0; }
    }

    private static final long DEFAULT_TIMEOUT_SECONDS = 600;

    private final BrainProperties brainProperties;

    private boolean enabled() {
        return brainProperties.sandbox() != null && brainProperties.sandbox().enabled();
    }

    private long timeoutSeconds() {
        if (brainProperties.sandbox() == null || brainProperties.sandbox().timeoutSeconds() <= 0) {
            return DEFAULT_TIMEOUT_SECONDS;
        }
        return brainProperties.sandbox().timeoutSeconds();
    }

    private String dockerImage() {
        if (brainProperties.sandbox() == null) return null;
        String image = brainProperties.sandbox().dockerImage();
        return image == null || image.isBlank() ? null : image;
    }

    private long memoryMb() {
        if (brainProperties.sandbox() == null || brainProperties.sandbox().memoryMb() <= 0) return 2048L;
        return brainProperties.sandbox().memoryMb();
    }

    private double cpus() {
        if (brainProperties.sandbox() == null || brainProperties.sandbox().cpus() <= 0) return 1.0;
        return brainProperties.sandbox().cpus();
    }

    public BuildTool detectBuildTool(Path workingDir) {
        if (workingDir == null || !Files.isDirectory(workingDir)) return BuildTool.UNKNOWN;
        if (Files.exists(workingDir.resolve("build.gradle"))
                || Files.exists(workingDir.resolve("build.gradle.kts"))
                || Files.exists(workingDir.resolve("gradlew"))) return BuildTool.GRADLE;
        if (Files.exists(workingDir.resolve("pom.xml"))) return BuildTool.MAVEN;
        if (Files.exists(workingDir.resolve("package.json"))) return BuildTool.NPM;
        return BuildTool.UNKNOWN;
    }

    public ValidationResult validateNode(Path workingDir, Map<String, String> envOverrides) {
        BuildTool tool = detectBuildTool(workingDir);
        if (!enabled()) {
            log.debug("Sandbox disabled — skipping validation for {}", workingDir);
            return new ValidationResult(true, true, "skipped (brain.sandbox.enabled=false)", "", 0, tool);
        }
        List<String> innerCommand = switch (tool) {
            case GRADLE -> gradleCommand();
            case MAVEN  -> List.of("mvn", "-q", "compile", "test");
            case NPM    -> List.of("npm", "test", "--silent");
            default     -> null;
        };
        if (innerCommand == null) {
            return new ValidationResult(false, false, "", "Unknown build tool", 1, tool);
        }
        return runProcess(workingDir, envOverrides, wrapWithDocker(workingDir, innerCommand), tool);
    }

    private List<String> gradleCommand() {
        return List.of("gradle", "compileJava", "test", "--no-daemon", "--offline");
    }

    private List<String> wrapWithDocker(Path workingDir, List<String> innerCommand) {
        String image = dockerImage();
        if (image == null) return innerCommand;
        List<String> docker = new java.util.ArrayList<>();
        docker.add("docker");
        docker.add("run");
        docker.add("--rm");
        docker.add("--network=none");
        docker.add("--read-only");
        docker.add("--tmpfs");
        docker.add("/tmp:rw,size=512m");
        docker.add("--memory=" + memoryMb() + "m");
        docker.add("--cpus=" + cpus());
        docker.add("-v");
        docker.add(workingDir.toAbsolutePath() + ":/work:rw");
        docker.add("-w");
        docker.add("/work");
        docker.add(image);
        docker.addAll(innerCommand);
        return docker;
    }

    private ValidationResult runProcess(Path workingDir, Map<String, String> envOverrides,
                                         List<String> command, BuildTool tool) {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDir.toFile());
        pb.environment().clear();
        applySafeEnv(pb);
        if (envOverrides != null) pb.environment().putAll(envOverrides);
        pb.redirectErrorStream(false);

        try {
            Process p = pb.start();
            long timeout = timeoutSeconds();
            java.util.concurrent.CompletableFuture<String> stdoutFuture =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> readStream(p.getInputStream()));
            java.util.concurrent.CompletableFuture<String> stderrFuture =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> readStream(p.getErrorStream()));
            boolean finished = p.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                stdoutFuture.cancel(true);
                stderrFuture.cancel(true);
                return new ValidationResult(false, false, "", "timeout after " + timeout + "s", 124, tool);
            }
            String stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(5, TimeUnit.SECONDS);
            int code = p.exitValue();
            boolean compiled = code == 0 || !stderr.contains("error:");
            boolean testsPassed = code == 0;
            log.info("Sandbox run for {} exited code={} tool={}", workingDir, code, tool);
            return new ValidationResult(compiled, testsPassed, stdout, stderr, code, tool);
        } catch (Exception e) {
            log.warn("Sandbox run failed for {}: {}", workingDir, e.getMessage());
            return new ValidationResult(false, false, "", e.getMessage(), 2, tool);
        }
    }

    private static final java.util.List<String> SAFE_ENV_KEYS = java.util.List.of(
            "PATH", "HOME", "JAVA_HOME", "LANG", "LC_ALL");

    private void applySafeEnv(ProcessBuilder pb) {
        for (String key : SAFE_ENV_KEYS) {
            String value = System.getenv(key);
            if (value != null && !value.isBlank()) pb.environment().put(key, value);
        }
    }

    private String readStream(java.io.InputStream in) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int chars = 0;
            while ((line = reader.readLine()) != null && chars < 100_000) {
                sb.append(line).append('\n');
                chars += line.length() + 1;
            }
        } catch (Exception e) {
            log.debug("Sandbox stream read interrupted: {}", e.getMessage());
        }
        return sb.toString();
    }

    public List<String> extractFailures(String stdout, String stderr) {
        List<String> failures = new ArrayList<>();
        for (String line : (stdout + "\n" + stderr).split("\n")) {
            String lower = line.toLowerCase();
            if (lower.contains("error:") || lower.contains("failed") || lower.contains("compilation failure")) {
                failures.add(line.trim());
            }
            if (failures.size() >= 25) break;
        }
        return failures;
    }
}
