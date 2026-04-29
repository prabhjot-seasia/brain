package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.InfraStackNode;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Log4j2
@Component
public class CdkPythonParser implements ArtifactParser {

    private static final List<String> CDK_DIRECTORIES = List.of(".infra", "cdk", "infra");
    private static final Pattern STACK_CLASS_DECL = Pattern.compile(
            "(?m)^class\\s+(\\w+Stack)\\s*\\(([^)]*)\\)\\s*:");
    private static final Pattern CONSTRUCT_USAGE = Pattern.compile(
            "(?m)\\b(aws_\\w+|aws-cdk-lib\\.aws_\\w+|aws_cdk\\.aws_\\w+)\\b");

    @Override
    public String name() {
        return "CdkPythonParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        for (String dir : CDK_DIRECTORIES) {
            Path candidate = context.projectPath().resolve(dir);
            if (Files.isDirectory(candidate) && hasPythonFiles(candidate)) return true;
        }
        return false;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, InfraStackNode> stacksById = new LinkedHashMap<>();

        for (String dir : CDK_DIRECTORIES) {
            Path root = context.projectPath().resolve(dir);
            if (!Files.isDirectory(root)) continue;
            walk(root, context, stacksById);
        }

        attachStacks(context.projectNode(), stacksById.values());
        log.info("CdkPythonParser ingested {} CDK stacks for project={}",
                stacksById.size(), context.projectId());
        return ParseResult.of(Map.of("stacks", stacksById.size()));
    }

    private boolean hasPythonFiles(Path root) {
        try (Stream<Path> stream = Files.walk(root, 4)) {
            return stream.filter(p -> !IngestPathFilter.isSkippable(p))
                    .filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".py"));
        } catch (IOException e) {
            return false;
        }
    }

    private void walk(Path root, IngestionContext context, Map<String, InfraStackNode> stacksById) {
        try (Stream<Path> stream = Files.walk(root, 6)) {
            List<Path> pyFiles = stream.filter(p -> !IngestPathFilter.isSkippable(p))
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".py"))
                    .toList();
            for (Path file : pyFiles) {
                processFile(file, context, stacksById);
            }
        } catch (IOException e) {
            log.warn("CdkPythonParser failed to walk {}: {}", root, e.getMessage());
        }
    }

    private void processFile(Path file, IngestionContext context, Map<String, InfraStackNode> stacksById) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            log.debug("CdkPythonParser skipped {}: {}", file, e.getMessage());
            return;
        }
        Matcher stacks = STACK_CLASS_DECL.matcher(content);
        if (!stacks.find()) return;

        String relativePath = context.projectPath().relativize(file).toString();
        Set<String> constructTypes = new LinkedHashSet<>();
        Matcher constructs = CONSTRUCT_USAGE.matcher(content);
        while (constructs.find()) {
            constructTypes.add(simplifyConstructName(constructs.group(1)));
        }

        stacks.reset();
        while (stacks.find()) {
            String stackName = stacks.group(1);
            String id = context.projectId() + ":stack:" + stackName;
            stacksById.computeIfAbsent(id, k -> {
                InfraStackNode stack = new InfraStackNode();
                stack.setId(id);
                stack.setProjectId(context.projectId());
                stack.setName(stackName);
                stack.setInfraType("CDK_PYTHON");
                stack.setPath(relativePath);
                stack.getConstructTypes().addAll(constructTypes);
                return stack;
            });
        }
    }

    private String simplifyConstructName(String raw) {
        if (raw.startsWith("aws_cdk.")) return raw.substring("aws_cdk.".length());
        if (raw.startsWith("aws-cdk-lib.")) return raw.substring("aws-cdk-lib.".length());
        return raw;
    }

    private void attachStacks(ProjectNode projectNode, Iterable<InfraStackNode> stacks) {
        Set<String> existing = new HashSet<>();
        projectNode.getInfraStacks().forEach(s -> existing.add(s.getId()));
        for (InfraStackNode stack : stacks) {
            if (existing.add(stack.getId())) {
                projectNode.getInfraStacks().add(stack);
            }
        }
    }
}
