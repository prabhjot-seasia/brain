package com.assurant.brain.service;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

@Log4j2
@Service
public class ProjectDetector {

    public record DetectedMetadata(String language, String framework, String buildTool) {}

    public DetectedMetadata detect(Path root) {
        if (fileExists(root, "build.gradle") || fileExists(root, "build.gradle.kts")) {
            String framework = containsText(root, "build.gradle", "org.springframework.boot")
                    || containsText(root, "build.gradle.kts", "org.springframework.boot")
                    ? "spring-boot" : null;
            return new DetectedMetadata("java", framework, "gradle");
        }

        if (fileExists(root, "pom.xml")) {
            String content = readFile(root, "pom.xml");
            String framework = content.contains("spring-boot-starter-parent")
                    || content.contains("spring-boot-starter-web") ? "spring-boot" : null;
            return new DetectedMetadata("java", framework, "maven");
        }

        if (fileExists(root, "package.json")) {
            String language = fileExists(root, "tsconfig.json") ? "typescript" : "javascript";
            String framework = detectNodeFramework(root);
            return new DetectedMetadata(language, framework, "npm");
        }

        if (fileExists(root, "pyproject.toml") || fileExists(root, "requirements.txt") || fileExists(root, "setup.py")) {
            String framework = detectPythonFramework(root);
            String buildTool = fileExists(root, "pyproject.toml") ? "poetry" : "pip";
            return new DetectedMetadata("python", framework, buildTool);
        }

        if (fileExists(root, "go.mod")) {
            String framework = detectGoFramework(root);
            return new DetectedMetadata("go", framework, "go-modules");
        }

        if (fileExists(root, "Cargo.toml")) {
            return new DetectedMetadata("rust", null, "cargo");
        }

        log.warn("Could not detect project metadata in {}", root);
        return new DetectedMetadata("unknown", null, null);
    }

    private String detectNodeFramework(Path root) {
        String content = readFile(root, "package.json");
        if (content.contains("\"react\"")) return "react";
        if (content.contains("\"next\"")) return "next";
        if (content.contains("\"express\"")) return "express";
        if (content.contains("\"@angular/core\"")) return "angular";
        if (content.contains("\"vue\"")) return "vue";
        return null;
    }

    private String detectPythonFramework(Path root) {
        String requirements = readFile(root, "requirements.txt");
        String pyproject = readFile(root, "pyproject.toml");
        String combined = requirements + pyproject;
        if (combined.contains("django") || combined.contains("Django")) return "django";
        if (combined.contains("fastapi") || combined.contains("FastAPI")) return "fastapi";
        if (combined.contains("flask") || combined.contains("Flask")) return "flask";
        return null;
    }

    private String detectGoFramework(Path root) {
        String content = readFile(root, "go.mod");
        if (content.contains("github.com/gin-gonic/gin")) return "gin";
        if (content.contains("github.com/labstack/echo")) return "echo";
        return null;
    }

    private boolean fileExists(Path root, String filename) {
        return Files.exists(root.resolve(filename));
    }

    private boolean containsText(Path root, String filename, String text) {
        return readFile(root, filename).contains(text);
    }

    private String readFile(Path root, String filename) {
        Path file = root.resolve(filename);
        if (!Files.exists(file)) return "";
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Could not read {}: {}", file, e.getMessage());
            return "";
        }
    }
}
