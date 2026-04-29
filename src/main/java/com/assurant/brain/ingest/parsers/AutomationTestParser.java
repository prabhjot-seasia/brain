package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.AutomationTestNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Log4j2
@Component
public class AutomationTestParser implements ArtifactParser {

    private static final List<String> SCAN_DIRS = List.of(
            "e2e", "tests/e2e", "cypress", "playwright", "selenium", "automation");

    private static final Pattern CYPRESS_SUFFIX = Pattern.compile(".*\\.cy\\.(js|ts|jsx|tsx)$");
    private static final Pattern PLAYWRIGHT_SUFFIX = Pattern.compile(".*\\.spec\\.(js|ts|jsx|tsx)$");
    private static final Pattern SELENIUM_HINT = Pattern.compile("(?i)\\b(WebDriver|Selenium)\\b");

    private static final int MAX_FILES = 500;
    private static final int MAX_FILE_BYTES = 200_000;

    @Override
    public String name() {
        return "AutomationTestParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        for (String dir : SCAN_DIRS) {
            Path candidate = context.projectPath().resolve(dir);
            if (Files.isDirectory(candidate)) return true;
        }
        return false;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        ProjectNode projectNode = context.projectNode();
        int testsAdded = 0;
        int filesScanned = 0;

        for (String dir : SCAN_DIRS) {
            Path root = context.projectPath().resolve(dir);
            if (!Files.isDirectory(root)) continue;

            try (var stream = IngestPathFilter.safeWalk(context.projectPath(), root)) {
                List<Path> matches = stream
                        .filter(Files::isRegularFile)
                        .filter(this::isAutomationTestFile)
                        .toList();
                for (Path file : matches) {
                    if (testsAdded >= MAX_FILES) break;
                    filesScanned++;
                    try {
                        AutomationTestNode node = build(context, root, file);
                        if (node != null) {
                            projectNode.getAutomationTests().add(node);
                            testsAdded++;
                        }
                    } catch (IOException | RuntimeException e) {
                        log.debug("AutomationTestParser skipped {}: {}", file, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.debug("AutomationTestParser walk failed for {}: {}", root, e.getMessage());
            }
        }

        return ParseResult.of(Map.of(
                "automationTestFiles", filesScanned,
                "automationTestNodes", testsAdded));
    }

    private boolean isAutomationTestFile(Path p) {
        String name = p.getFileName().toString();
        return CYPRESS_SUFFIX.matcher(name).matches()
                || PLAYWRIGHT_SUFFIX.matcher(name).matches()
                || name.endsWith(".side")
                || (name.endsWith(".java") && containsSelenium(p))
                || (name.endsWith(".py") && containsSelenium(p));
    }

    private boolean containsSelenium(Path p) {
        try {
            byte[] bytes = IngestPathFilter.readAllBytesIfWithinLimit(p, MAX_FILE_BYTES);
            return SELENIUM_HINT.matcher(new String(bytes)).find();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private AutomationTestNode build(IngestionContext context, Path scanRoot, Path file) throws IOException {
        String relativePath = context.projectPath().relativize(file).toString();
        String name = file.getFileName().toString();
        AutomationTestNode node = new AutomationTestNode();
        node.setId(context.projectId() + ":" + relativePath);
        node.setRepoId(context.projectId());
        node.setFramework(detectFramework(name, file));
        node.setPath(relativePath);
        node.setTestFqn(toFqn(scanRoot.relativize(file).toString()));
        return node;
    }

    private String detectFramework(String fileName, Path file) {
        if (CYPRESS_SUFFIX.matcher(fileName).matches()) return "CYPRESS";
        if (PLAYWRIGHT_SUFFIX.matcher(fileName).matches()) return "PLAYWRIGHT";
        if (fileName.endsWith(".side")) return "SELENIUM_IDE";
        if (containsSelenium(file)) return "SELENIUM";
        return "UNKNOWN";
    }

    private String toFqn(String relative) {
        return relative.replace(java.io.File.separatorChar, '.').replaceAll("\\.[^.]+$", "");
    }
}
