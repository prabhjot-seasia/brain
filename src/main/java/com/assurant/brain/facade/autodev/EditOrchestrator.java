package com.assurant.brain.facade.autodev;

import com.assurant.brain.codegen.CodeGeneratorService;
import com.assurant.brain.codegen.DependencyPropagationAnalyzer;
import com.assurant.brain.codegen.DiffApplier;
import com.assurant.brain.codegen.DiffGenerator;
import com.assurant.brain.codegen.EditBoundaryEnforcer;
import com.assurant.brain.codegen.PlanGraph;
import com.assurant.brain.codegen.PlanNode;
import com.assurant.brain.codegen.SeamAnalyzer;
import com.assurant.brain.codegen.SymbolDictionary;
import com.assurant.brain.codegen.SymbolDictionaryBuilder;
import com.assurant.brain.codegen.SymbolGroundingValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Log4j2
@Service
@RequiredArgsConstructor
public class EditOrchestrator {

    private final DependencyPropagationAnalyzer propagationAnalyzer;
    private final SeamAnalyzer seamAnalyzer;
    private final CodeGeneratorService codeGeneratorService;
    private final DiffGenerator diffGenerator;
    private final DiffApplier diffApplier;
    private final ObjectMapper objectMapper;
    private final com.assurant.brain.sandbox.SandboxValidationService sandboxValidationService;
    private final com.assurant.brain.config.properties.BrainProperties brainProperties;
    private final SymbolDictionaryBuilder symbolDictionaryBuilder;
    private final SymbolGroundingValidator symbolGroundingValidator;
    private final EditBoundaryEnforcer editBoundaryEnforcer;

    public EditOrchestrationResult orchestrate(String projectId, String requirement,
                                                 List<PlanNode> seedEdits) {
        PlanGraph propagated = propagationAnalyzer.propagate(seedEdits);
        PlanGraph annotated = seamAnalyzer.annotate(propagated);

        log.info("EditOrchestrator running over {} plan node(s) for project={}",
                annotated.size(), projectId);

        Map<String, String> aggregatedFiles = new HashMap<>();
        List<String> nodeErrors = new ArrayList<>();

        for (PlanNode node : annotated.topologicalOrder()) {
            try {
                Map<String, String> previousSnapshot = new HashMap<>(aggregatedFiles);
                Map<String, String> nodeFiles;
                if (node.derived() && node.filePath() != null && aggregatedFiles.containsKey(node.filePath())) {
                    generateViaDiff(projectId, node, aggregatedFiles);
                    nodeFiles = aggregatedFiles;
                } else {
                    nodeFiles = codeGeneratorService.generateCode(projectId,
                            serializeNodeAsPlanFragment(node, requirement),
                            requirement);
                    if (nodeFiles != null) aggregatedFiles.putAll(nodeFiles);
                }
                enforceBoundary(node, previousSnapshot, nodeFiles, nodeErrors);
            } catch (Exception e) {
                String error = "node=" + node.blockId() + " (" + node.targetSymbol() + "): "
                        + e.getMessage();
                log.warn("EditOrchestrator per-node code generation failed — {} — continuing", error);
                nodeErrors.add(error);
            }
        }

        runSymbolGrounding(projectId, aggregatedFiles, nodeErrors);
        runSandboxValidation(projectId, aggregatedFiles, nodeErrors);

        return new EditOrchestrationResult(projectId, annotated, aggregatedFiles, nodeErrors);
    }

    private void enforceBoundary(PlanNode node,
                                   Map<String, String> previousSnapshot,
                                   Map<String, String> nodeFiles,
                                   List<String> nodeErrors) {
        if (nodeFiles == null || nodeFiles.isEmpty()) return;
        Map<String, String> changedOnly = new HashMap<>();
        for (Map.Entry<String, String> entry : nodeFiles.entrySet()) {
            String prev = previousSnapshot.get(entry.getKey());
            if (prev == null || !prev.equals(entry.getValue())) {
                changedOnly.put(entry.getKey(), entry.getValue());
            }
        }
        if (changedOnly.isEmpty()) return;
        var result = editBoundaryEnforcer.enforce(node.effectiveBoundary(),
                previousSnapshot, changedOnly);
        if (!result.ok()) {
            log.warn("EditBoundary violations for node={} on project={}: {}",
                    node.blockId(), node.projectId(), result.violations().size());
            for (String v : result.violations()) {
                nodeErrors.add("node=" + node.blockId() + ": " + v);
            }
        }
    }

    private void runSymbolGrounding(String projectId, Map<String, String> aggregatedFiles,
                                     List<String> nodeErrors) {
        if (aggregatedFiles.isEmpty()) return;
        SymbolDictionary dict = symbolDictionaryBuilder.build(projectId);
        var result = symbolGroundingValidator.validate(aggregatedFiles, dict);
        if (!result.ok()) {
            log.warn("Symbol grounding flagged {} hallucinated reference(s) for project={}",
                    result.issues().size(), projectId);
            result.issues().forEach(i -> nodeErrors.add("grounding: " + i));
        }
    }

    private static final java.util.Set<String> BUILD_CONFIG_FILES = java.util.Set.of(
            "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "pom.xml", "package.json", "package-lock.json", "yarn.lock");

    private boolean isBuildConfig(String key) {
        if (key == null) return false;
        String normalized = key.replace('\\', '/').toLowerCase();
        String name = normalized.contains("/")
                ? normalized.substring(normalized.lastIndexOf('/') + 1)
                : normalized;
        return BUILD_CONFIG_FILES.contains(name);
    }

    private boolean requireSandbox() {
        return brainProperties.autodev() != null && brainProperties.autodev().requireSandbox();
    }

    private boolean sandboxEnabled() {
        return brainProperties.sandbox() != null && brainProperties.sandbox().enabled();
    }

    private void runSandboxValidation(String projectId, Map<String, String> aggregatedFiles,
                                       List<String> nodeErrors) {
        if (aggregatedFiles.isEmpty()) return;
        if (requireSandbox() && !sandboxEnabled()) {
            String error = "sandbox: brain.autodev.require-sandbox=true but brain.sandbox.enabled=false — "
                    + "autodev pipeline refuses to ship code that has not been compile + test verified";
            log.error("Sandbox required but disabled for project={}", projectId);
            nodeErrors.add(error);
            return;
        }
        for (String key : aggregatedFiles.keySet()) {
            if (isBuildConfig(key)) {
                String error = "sandbox: refusing to validate plan that modifies build config "
                        + key + " — re-route through JARVIS infra review";
                log.warn("Sandbox refused: project={} touched build config {}", projectId, key);
                nodeErrors.add(error);
                return;
            }
        }
        java.nio.file.Path tempRoot = null;
        try {
            tempRoot = java.nio.file.Files.createTempDirectory("brain-sandbox-" + projectId + "-");
            for (var entry : aggregatedFiles.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank() || key.startsWith("/") || key.startsWith("\\")
                        || key.contains("..") || key.contains(":") || key.contains("\0")) {
                    log.warn("Sandbox skipping unsafe path key for project={}: {}", projectId, key);
                    continue;
                }
                java.nio.file.Path target = tempRoot.resolve(key).normalize();
                if (!target.startsWith(tempRoot)) {
                    log.warn("Sandbox rejected path-traversal entry for project={}: {}", projectId, key);
                    continue;
                }
                java.nio.file.Path parent = target.getParent();
                if (parent == null || !parent.startsWith(tempRoot)) {
                    log.warn("Sandbox rejected entry with no safe parent for project={}: {}", projectId, key);
                    continue;
                }
                java.nio.file.Files.createDirectories(parent);
                java.nio.file.Files.writeString(target, entry.getValue());
            }
            var result = sandboxValidationService.validateNode(tempRoot, java.util.Map.of());
            if (!result.ok() && !result.stdout().contains("skipped")) {
                log.warn("Sandbox validation failed for project={} buildTool={} exitCode={}",
                        projectId, result.buildTool(), result.exitCode());
                sandboxValidationService.extractFailures(result.stdout(), result.stderr())
                        .forEach(f -> nodeErrors.add("sandbox: " + f));
                if (requireSandbox()) {
                    nodeErrors.add("sandbox: brain.autodev.require-sandbox=true blocks PR creation "
                            + "until compile + tests are green");
                }
            }
        } catch (Exception e) {
            log.warn("Sandbox setup failed for project={}: {}", projectId, e.getMessage());
            if (requireSandbox()) {
                nodeErrors.add("sandbox: validation could not run (" + e.getMessage()
                        + ") and brain.autodev.require-sandbox=true — refusing to ship");
            }
        } finally {
            if (tempRoot != null) deleteRecursively(tempRoot);
        }
    }

    private void deleteRecursively(java.nio.file.Path path) {
        try (var stream = java.nio.file.Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(this::deleteOne);
        } catch (Exception e) {
            log.debug("Sandbox cleanup walk failed for {}: {}", path, e.getMessage());
        }
    }

    private void deleteOne(java.nio.file.Path p) {
        try {
            java.nio.file.Files.deleteIfExists(p);
        } catch (Exception e) {
            log.debug("Sandbox cleanup delete failed for {}: {}", p, e.getMessage());
        }
    }

    private void generateViaDiff(String projectId, PlanNode node, Map<String, String> aggregatedFiles) {
        String existingContent = aggregatedFiles.get(node.filePath());
        String instruction = node.instruction() == null ? "" : node.instruction();

        log.info("Using diff-based generation for derived node={} file={}",
                node.blockId(), node.filePath());

        String diff = diffGenerator.generateDiff(existingContent, node.filePath(), instruction, "");
        String patched = diffApplier.apply(existingContent, diff);
        aggregatedFiles.put(node.filePath(), patched);
    }

    private String serializeNodeAsPlanFragment(PlanNode node, String requirement) {
        ObjectNode plan = objectMapper.createObjectNode();
        plan.put("requirement", requirement);
        plan.put("blockId",      node.blockId());
        plan.put("filePath",     node.filePath());
        plan.put("targetSymbol", node.targetSymbol() == null ? "" : node.targetSymbol());
        plan.put("kind",         node.kind() == null ? "UNKNOWN" : node.kind().name());
        plan.put("instruction",  node.instruction());
        plan.put("derived",      node.derived());
        plan.put("seamFlag",     node.seamFlag() == null ? "UNKNOWN" : node.seamFlag().name());

        var files = plan.putArray("affectedFiles");
        ObjectNode f = objectMapper.createObjectNode();
        f.put("path",       node.filePath() == null ? "unknown" : node.filePath());
        f.put("reason",     node.instruction() == null ? "" : node.instruction());
        f.put("confidence", 0.9);
        files.add(f);

        var steps = plan.putArray("steps");
        ObjectNode s = objectMapper.createObjectNode();
        s.put("order",       1);
        s.put("description", node.instruction() == null ? "" : node.instruction());
        s.putArray("files").add(node.filePath() == null ? "unknown" : node.filePath());
        steps.add(s);

        return plan.toString();
    }
}
