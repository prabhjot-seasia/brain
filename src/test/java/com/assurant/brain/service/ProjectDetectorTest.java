package com.assurant.brain.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProjectDetector — auto-detection of language, framework, build tool")
class ProjectDetectorTest {

    private final ProjectDetector detector = new ProjectDetector();

    @Test
    void gradleSpringBoot(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle"),
                "plugins { id 'org.springframework.boot' }", StandardCharsets.UTF_8);
        var result = detector.detect(root);
        assertThat(result.language()).isEqualTo("java");
        assertThat(result.framework()).isEqualTo("spring-boot");
        assertThat(result.buildTool()).isEqualTo("gradle");
    }

    @Test
    void gradleWithoutSpringBoot(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }", StandardCharsets.UTF_8);
        var result = detector.detect(root);
        assertThat(result.language()).isEqualTo("java");
        assertThat(result.framework()).isNull();
        assertThat(result.buildTool()).isEqualTo("gradle");
    }

    @Test
    void gradleKts(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle.kts"),
                "plugins { id(\"org.springframework.boot\") }", StandardCharsets.UTF_8);
        var result = detector.detect(root);
        assertThat(result.language()).isEqualTo("java");
        assertThat(result.framework()).isEqualTo("spring-boot");
        assertThat(result.buildTool()).isEqualTo("gradle");
    }

    @Test
    void mavenSpringBoot(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"),
                "<parent><artifactId>spring-boot-starter-parent</artifactId></parent>", StandardCharsets.UTF_8);
        var result = detector.detect(root);
        assertThat(result.language()).isEqualTo("java");
        assertThat(result.framework()).isEqualTo("spring-boot");
        assertThat(result.buildTool()).isEqualTo("maven");
    }

    @Test
    void mavenWithoutSpringBoot(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project></project>", StandardCharsets.UTF_8);
        var result = detector.detect(root);
        assertThat(result.language()).isEqualTo("java");
        assertThat(result.framework()).isNull();
        assertThat(result.buildTool()).isEqualTo("maven");
    }

    @Test
    void typescriptReact(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("package.json"),
                "{\"dependencies\":{\"react\":\"^18.0.0\"}}", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("tsconfig.json"), "{}", StandardCharsets.UTF_8);
        var result = detector.detect(root);
        assertThat(result.language()).isEqualTo("typescript");
        assertThat(result.framework()).isEqualTo("react");
        assertThat(result.buildTool()).isEqualTo("npm");
    }

    @Test
    void javascriptExpress(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("package.json"),
                "{\"dependencies\":{\"express\":\"^4.0.0\"}}", StandardCharsets.UTF_8);
        var result = detector.detect(root);
        assertThat(result.language()).isEqualTo("javascript");
        assertThat(result.framework()).isEqualTo("express");
        assertThat(result.buildTool()).isEqualTo("npm");
    }

    @Test
    void pythonDjango(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("requirements.txt"), "django==4.2\ncelery", StandardCharsets.UTF_8);
        var result = detector.detect(root);
        assertThat(result.language()).isEqualTo("python");
        assertThat(result.framework()).isEqualTo("django");
        assertThat(result.buildTool()).isEqualTo("pip");
    }

    @Test
    void pythonPoetryFastapi(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("pyproject.toml"),
                "[tool.poetry.dependencies]\nfastapi = \"^0.100\"", StandardCharsets.UTF_8);
        var result = detector.detect(root);
        assertThat(result.language()).isEqualTo("python");
        assertThat(result.framework()).isEqualTo("fastapi");
        assertThat(result.buildTool()).isEqualTo("poetry");
    }

    @Test
    void goGin(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("go.mod"),
                "module example.com/app\nrequire github.com/gin-gonic/gin v1.9.0", StandardCharsets.UTF_8);
        var result = detector.detect(root);
        assertThat(result.language()).isEqualTo("go");
        assertThat(result.framework()).isEqualTo("gin");
        assertThat(result.buildTool()).isEqualTo("go-modules");
    }

    @Test
    void rust(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("Cargo.toml"), "[package]\nname=\"app\"", StandardCharsets.UTF_8);
        var result = detector.detect(root);
        assertThat(result.language()).isEqualTo("rust");
        assertThat(result.buildTool()).isEqualTo("cargo");
    }

    @Test
    void emptyDirectory(@TempDir Path root) {
        var result = detector.detect(root);
        assertThat(result.language()).isEqualTo("unknown");
        assertThat(result.framework()).isNull();
        assertThat(result.buildTool()).isNull();
    }

    @Test
    void gradleTakesPriorityOverMaven(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("pom.xml"), "<project></project>", StandardCharsets.UTF_8);
        var result = detector.detect(root);
        assertThat(result.buildTool()).isEqualTo("gradle");
    }
}
