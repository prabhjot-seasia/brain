package com.assurant.brain.ingest;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Log4j2
@Component
public class RepoKindClassifier {

    private static final List<String> APP_NAME_HINTS = List.of("-app", "-service", "-api", "-server", "-ui");
    private static final List<String> REGISTRY_NAME_HINTS = List.of("-config-files", "-api-document", "-platform-", "-catalog");
    private static final Pattern PUBLISH_CONFIG_PATTERN = Pattern.compile("\"publishConfig\"\\s*:\\s*\\{");

    public RepoKind classify(Path projectPath, String projectName) {
        if (projectPath == null || !Files.isDirectory(projectPath)) {
            log.debug("Cannot classify {} — path does not exist", projectPath);
            return RepoKind.UNKNOWN;
        }

        if (matchesScripts(projectPath)) return RepoKind.SCRIPTS;
        if (matchesCrossCuttingInfra(projectPath)) return RepoKind.CROSS_CUTTING_INFRA;
        if (matchesRegistry(projectName)) return RepoKind.REGISTRY;
        if (matchesClientLibrary(projectPath)) return RepoKind.CLIENT_LIBRARY;
        if (matchesApplication(projectPath, projectName)) return RepoKind.APPLICATION;

        return RepoKind.UNKNOWN;
    }

    private boolean matchesApplication(Path projectPath, String projectName) {
        boolean hasDockerfile = Files.exists(projectPath.resolve("Dockerfile"));
        boolean hasAppSpec = Files.exists(projectPath.resolve("appspec.yml"))
                || Files.exists(projectPath.resolve("appspec.yaml"));
        boolean hasJavaController = anyMatch(projectPath, p -> {
            String fileName = p.getFileName().toString();
            return fileName.endsWith("Controller.java");
        }, 4);
        boolean nameLooksApp = projectName != null
                && APP_NAME_HINTS.stream().anyMatch(projectName::endsWith);
        return hasDockerfile || hasAppSpec || hasJavaController || nameLooksApp;
    }

    private boolean matchesCrossCuttingInfra(Path projectPath) {
        boolean topLevelInfraDir = Files.isDirectory(projectPath.resolve(".cdk"))
                || Files.isDirectory(projectPath.resolve("terraform"))
                || Files.isDirectory(projectPath.resolve("modules"));
        if (!topLevelInfraDir) {
            return false;
        }
        boolean hasNoAppCode = !Files.isDirectory(projectPath.resolve("src/main/java"))
                && !Files.exists(projectPath.resolve("Dockerfile"));
        return hasNoAppCode;
    }

    private boolean matchesRegistry(String projectName) {
        if (projectName == null) return false;
        return REGISTRY_NAME_HINTS.stream().anyMatch(projectName::contains);
    }

    private boolean matchesClientLibrary(Path projectPath) {
        Path packageJson = projectPath.resolve("package.json");
        if (Files.exists(packageJson)) {
            try {
                String content = Files.readString(packageJson);
                if (PUBLISH_CONFIG_PATTERN.matcher(content).find()
                        && !Files.exists(projectPath.resolve("Dockerfile"))) {
                    return true;
                }
            } catch (IOException e) {
                log.debug("Could not read package.json at {} — skipping client-library detection", packageJson);
            }
        }
        return false;
    }

    private boolean matchesScripts(Path projectPath) {
        boolean hasWorkflows = Files.isDirectory(projectPath.resolve(".github/workflows"));
        if (hasWorkflows) return false;
        long scriptCount = countMatching(projectPath, p -> {
            String name = p.getFileName().toString();
            return name.endsWith(".sh") || name.endsWith(".sql");
        }, 3);
        long codeCount = countMatching(projectPath, p -> {
            String name = p.getFileName().toString();
            return name.endsWith(".java") || name.endsWith(".ts") || name.endsWith(".tsx");
        }, 3);
        return scriptCount > 0 && codeCount == 0;
    }

    private boolean anyMatch(Path root, java.util.function.Predicate<Path> predicate, int maxDepth) {
        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            return stream.anyMatch(predicate);
        } catch (IOException e) {
            return false;
        }
    }

    private long countMatching(Path root, java.util.function.Predicate<Path> predicate, int maxDepth) {
        try (Stream<Path> stream = Files.walk(root, maxDepth)) {
            return stream.filter(predicate).limit(1).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
