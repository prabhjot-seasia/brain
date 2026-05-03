package com.assurant.brain.sandbox;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.assurant.brain.config.properties.BrainProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SandboxValidationService")
class SandboxValidationServiceTest {

    private SandboxValidationService service;

    @BeforeEach
    void setup() {
        var sandbox = new BrainProperties.Sandbox(false, 30L, null, 2048L, 1.0);
        var props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, sandbox, null, null, null);
        service = new SandboxValidationService(props);
    }

    @Test
    @DisplayName("detectBuildTool finds Gradle from build.gradle")
    void detectsGradle(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("build.gradle"), "plugins { id 'java' }");
        assertThat(service.detectBuildTool(dir)).isEqualTo(SandboxValidationService.BuildTool.GRADLE);
    }

    @Test
    @DisplayName("detectBuildTool finds Maven from pom.xml")
    void detectsMaven(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        assertThat(service.detectBuildTool(dir)).isEqualTo(SandboxValidationService.BuildTool.MAVEN);
    }

    @Test
    @DisplayName("detectBuildTool finds NPM from package.json")
    void detectsNpm(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("package.json"), "{}");
        assertThat(service.detectBuildTool(dir)).isEqualTo(SandboxValidationService.BuildTool.NPM);
    }

    @Test
    @DisplayName("detectBuildTool returns UNKNOWN when no markers present")
    void unknownWhenEmpty(@TempDir Path dir) {
        assertThat(service.detectBuildTool(dir)).isEqualTo(SandboxValidationService.BuildTool.UNKNOWN);
    }

    @Test
    @DisplayName("validateNode returns ok=true and \"skipped\" stdout when sandbox disabled")
    void disabledSkips(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("build.gradle"), "plugins { id 'java' }");

        var result = service.validateNode(dir, Map.of());

        assertThat(result.ok()).isTrue();
        assertThat(result.stdout()).contains("skipped");
        assertThat(result.buildTool()).isEqualTo(SandboxValidationService.BuildTool.GRADLE);
    }

    @Test
    @DisplayName("extractFailures pulls error and FAILED lines from output")
    void extractFailuresFiltersLines() {
        String stdout = "BUILD STARTED\nTask :compileJava\n";
        String stderr = "Foo.java:10: error: cannot find symbol\nFAILED: tests\n";

        var failures = service.extractFailures(stdout, stderr);

        assertThat(failures).anyMatch(f -> f.contains("cannot find symbol"));
        assertThat(failures).anyMatch(f -> f.toLowerCase().contains("failed: tests"));
    }

    @Test
    @DisplayName("gradleCommand always uses system gradle (never gradlew from temp dir)")
    void gradleCommandRejectsGradlewFromTempDir() {
        @SuppressWarnings("unchecked")
        java.util.List<String> command = org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(service, "gradleCommand");

        assertThat(command).startsWith("gradle");
        assertThat(command).contains("--offline").contains("--no-daemon");
        assertThat(command.get(0)).doesNotContain("/");
    }

    @Test
    @DisplayName("wrapWithDocker returns inner command unchanged when dockerImage is unset")
    void wrapWithDockerNoOp(@TempDir Path dir) {
        @SuppressWarnings("unchecked")
        java.util.List<String> wrapped = org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(service, "wrapWithDocker", dir, java.util.List.of("gradle", "test"));

        assertThat(wrapped).containsExactly("gradle", "test");
    }

    @Test
    @DisplayName("wrapWithDocker prefixes docker run with --network=none, --read-only, memory + cpu caps")
    void wrapWithDockerWrapsCommand(@TempDir Path dir) {
        var sandbox = new BrainProperties.Sandbox(true, 60L,
                "project-brain/sandbox:latest", 1024L, 0.5);
        var props = new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, sandbox, null, null, null);
        var docked = new SandboxValidationService(props);

        @SuppressWarnings("unchecked")
        java.util.List<String> wrapped = org.springframework.test.util.ReflectionTestUtils
                .invokeMethod(docked, "wrapWithDocker", dir, java.util.List.of("gradle", "test"));

        assertThat(wrapped).startsWith("docker", "run", "--rm", "--network=none", "--read-only");
        assertThat(wrapped).contains("--memory=1024m", "--cpus=0.5");
        assertThat(wrapped).contains("project-brain/sandbox:latest");
        assertThat(wrapped).endsWith("gradle", "test");
    }
}
