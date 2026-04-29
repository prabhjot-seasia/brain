package com.assurant.brain.facade.autodev;

import com.assurant.brain.codegen.CodeGeneratorService;
import com.assurant.brain.codegen.DependencyPropagationAnalyzer;
import com.assurant.brain.codegen.DiffApplier;
import com.assurant.brain.codegen.DiffGenerator;
import com.assurant.brain.codegen.PlanEdge;
import com.assurant.brain.codegen.PlanGraph;
import com.assurant.brain.codegen.PlanNode;
import com.assurant.brain.codegen.SeamAnalyzer;
import com.assurant.brain.enums.ChangeKind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("EditOrchestrator")
class EditOrchestratorTest {

    private DependencyPropagationAnalyzer propagation;
    private SeamAnalyzer seam;
    private CodeGeneratorService codeGenerator;
    private DiffGenerator diffGenerator;
    private DiffApplier diffApplier;
    private EditOrchestrator orchestrator;

    @BeforeEach
    void setup() {
        propagation = mock(DependencyPropagationAnalyzer.class);
        seam = mock(SeamAnalyzer.class);
        codeGenerator = mock(CodeGeneratorService.class);
        diffGenerator = mock(DiffGenerator.class);
        diffApplier = mock(DiffApplier.class);
        var sandbox = mock(com.assurant.brain.sandbox.SandboxValidationService.class);
        when(sandbox.validateNode(any(), any())).thenReturn(
                new com.assurant.brain.sandbox.SandboxValidationService.ValidationResult(
                        true, true, "skipped (brain.sandbox.enabled=false)", "", 0,
                        com.assurant.brain.sandbox.SandboxValidationService.BuildTool.UNKNOWN));
        orchestrator = new EditOrchestrator(propagation, seam, codeGenerator,
                diffGenerator, diffApplier, new ObjectMapper(), sandbox);
    }

    @Test
    @DisplayName("orchestrate walks propagated plan nodes and aggregates files from code generator")
    void orchestrateAggregatesFiles() {
        PlanNode seed = node("seed-1", "com.example.Foo", "src/Foo.java");
        PlanGraph graph = new PlanGraph(List.of(seed), List.of());
        when(propagation.propagate(any())).thenReturn(graph);
        when(seam.annotate(graph)).thenReturn(graph);
        when(codeGenerator.generateCode(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("src/Foo.java", "new content"));

        EditOrchestrationResult result = orchestrator.orchestrate("proj-1", "req", List.of(seed));

        assertThat(result.files()).containsEntry("src/Foo.java", "new content");
        assertThat(result.hasErrors()).isFalse();
        verify(codeGenerator).generateCode(eq("proj-1"), anyString(), eq("req"));
    }

    @Test
    @DisplayName("orchestrate collects per-node errors without aborting")
    void perNodeErrorIsRecorded() {
        PlanNode a = node("seed-1", "com.example.Foo", "src/Foo.java");
        PlanNode b = node("seed-2", "com.example.Bar", "src/Bar.java");
        PlanGraph graph = new PlanGraph(List.of(a, b), List.of());
        when(propagation.propagate(any())).thenReturn(graph);
        when(seam.annotate(graph)).thenReturn(graph);
        when(codeGenerator.generateCode(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("LLM down"))
                .thenReturn(Map.of("src/Bar.java", "ok"));

        EditOrchestrationResult result = orchestrator.orchestrate("proj-1", "req", List.of(a, b));

        assertThat(result.files()).containsEntry("src/Bar.java", "ok");
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.nodeErrors()).hasSize(1);
    }

    @Test
    @DisplayName("orchestrate with empty propagated graph returns empty file map")
    void emptyGraphEmptyFiles() {
        when(propagation.propagate(any())).thenReturn(PlanGraph.empty());
        when(seam.annotate(PlanGraph.empty())).thenReturn(PlanGraph.empty());

        EditOrchestrationResult result = orchestrator.orchestrate("proj-1", "req", List.of());

        assertThat(result.files()).isEmpty();
        assertThat(result.hasErrors()).isFalse();
        verify(codeGenerator, never()).generateCode(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("derived node targeting an already-generated file uses diff-based generation")
    void derivedNodeUsesDiffPath() {
        PlanNode seed = node("seed-1", "com.example.Foo", "src/Foo.java");
        PlanNode derived = new PlanNode("derived-1", "proj-1", "src/Foo.java",
                "com.example.Caller", "update call site", ChangeKind.MODIFY_METHOD_BODY, true, null);
        PlanGraph graph = new PlanGraph(List.of(seed, derived),
                List.of(new PlanEdge("seed-1", "derived-1", "PROPAGATES_TO")));
        when(propagation.propagate(any())).thenReturn(graph);
        when(seam.annotate(graph)).thenReturn(graph);
        when(codeGenerator.generateCode(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("src/Foo.java", "original content"));
        when(diffGenerator.generateDiff(anyString(), anyString(), anyString(), anyString()))
                .thenReturn("@@ -1,1 +1,1 @@\n-original content\n+patched content");
        when(diffApplier.apply(anyString(), anyString()))
                .thenReturn("patched content");

        EditOrchestrationResult result = orchestrator.orchestrate("proj-1", "req", List.of(seed, derived));

        assertThat(result.files().get("src/Foo.java")).isEqualTo("patched content");
        verify(diffGenerator).generateDiff(eq("original content"), eq("src/Foo.java"), anyString(), anyString());
        verify(diffApplier).apply(eq("original content"), anyString());
    }

    @Test
    @DisplayName("all nodes failing still returns empty files with all errors collected")
    void allNodesFailStillReturnsResult() {
        PlanNode a = node("seed-1", "com.example.Foo", "src/Foo.java");
        PlanNode b = node("seed-2", "com.example.Bar", "src/Bar.java");
        PlanGraph graph = new PlanGraph(List.of(a, b), List.of());
        when(propagation.propagate(any())).thenReturn(graph);
        when(seam.annotate(graph)).thenReturn(graph);
        when(codeGenerator.generateCode(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("LLM timeout"));

        EditOrchestrationResult result = orchestrator.orchestrate("proj-1", "req", List.of(a, b));

        assertThat(result.files()).isEmpty();
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.nodeErrors()).hasSize(2);
        assertThat(result.nodeErrors().get(0)).contains("LLM timeout");
    }

    @Test
    @DisplayName("diff-based path falls back to error collection when DiffGenerator throws")
    void diffPathFallsBackOnError() {
        PlanNode seed = node("seed-1", "com.example.Foo", "src/Foo.java");
        PlanNode derived = new PlanNode("derived-1", "proj-1", "src/Foo.java",
                "com.example.Caller", "update call site", ChangeKind.MODIFY_METHOD_BODY, true, null);
        PlanGraph graph = new PlanGraph(List.of(seed, derived),
                List.of(new PlanEdge("seed-1", "derived-1", "PROPAGATES_TO")));
        when(propagation.propagate(any())).thenReturn(graph);
        when(seam.annotate(graph)).thenReturn(graph);
        when(codeGenerator.generateCode(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("src/Foo.java", "original"));
        when(diffGenerator.generateDiff(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("diff gen failed"));

        EditOrchestrationResult result = orchestrator.orchestrate("proj-1", "req", List.of(seed, derived));

        assertThat(result.files()).containsEntry("src/Foo.java", "original");
        assertThat(result.hasErrors()).isTrue();
        assertThat(result.nodeErrors()).hasSize(1);
        assertThat(result.nodeErrors().get(0)).contains("diff gen failed");
    }

    @Test
    @DisplayName("codeGenerator returning null file map is handled gracefully")
    void nullFileMapFromCodeGenerator() {
        PlanNode seed = node("seed-1", "com.example.Foo", "src/Foo.java");
        PlanGraph graph = new PlanGraph(List.of(seed), List.of());
        when(propagation.propagate(any())).thenReturn(graph);
        when(seam.annotate(graph)).thenReturn(graph);
        when(codeGenerator.generateCode(anyString(), anyString(), anyString()))
                .thenReturn(null);

        EditOrchestrationResult result = orchestrator.orchestrate("proj-1", "req", List.of(seed));

        assertThat(result.files()).isEmpty();
        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    @DisplayName("propagation analyzer exception is surfaced — orchestrator does not swallow it")
    void propagationAnalyzerExceptionBubbles() {
        when(propagation.propagate(any())).thenThrow(new RuntimeException("graph offline"));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> orchestrator.orchestrate("proj-1", "req", List.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("graph offline");
    }

    @Test
    @DisplayName("sandbox failure appends 'sandbox: ...' lines to nodeErrors")
    void sandboxFailureAppendsErrors() {
        PlanNode seed = node("seed-1", "com.example.Foo", "src/Foo.java");
        PlanGraph graph = new PlanGraph(List.of(seed), List.of());
        when(propagation.propagate(any())).thenReturn(graph);
        when(seam.annotate(graph)).thenReturn(graph);
        when(codeGenerator.generateCode(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("src/Foo.java", "class Foo {}"));

        var sandbox = (com.assurant.brain.sandbox.SandboxValidationService)
                org.springframework.test.util.ReflectionTestUtils.getField(orchestrator, "sandboxValidationService");
        when(sandbox.validateNode(any(), any())).thenReturn(
                new com.assurant.brain.sandbox.SandboxValidationService.ValidationResult(
                        false, false, "BUILD STARTED", "Foo.java:10: error: x", 1,
                        com.assurant.brain.sandbox.SandboxValidationService.BuildTool.GRADLE));
        when(sandbox.extractFailures(anyString(), anyString()))
                .thenReturn(List.of("Foo.java:10: error: x"));

        EditOrchestrationResult result = orchestrator.orchestrate("proj-1", "req", List.of(seed));

        assertThat(result.nodeErrors()).anyMatch(e -> e.startsWith("sandbox: "));
    }

    @Test
    @DisplayName("sandbox skipped (validation disabled) does not pollute nodeErrors")
    void sandboxSkippedDoesNotPollute() {
        PlanNode seed = node("seed-1", "com.example.Foo", "src/Foo.java");
        PlanGraph graph = new PlanGraph(List.of(seed), List.of());
        when(propagation.propagate(any())).thenReturn(graph);
        when(seam.annotate(graph)).thenReturn(graph);
        when(codeGenerator.generateCode(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("src/Foo.java", "class Foo {}"));

        EditOrchestrationResult result = orchestrator.orchestrate("proj-1", "req", List.of(seed));

        assertThat(result.nodeErrors()).noneMatch(e -> e.startsWith("sandbox: "));
    }

    @Test
    @DisplayName("sandbox refuses to validate plans that touch build config files")
    void sandboxRefusesBuildConfigChanges() {
        PlanNode seed = node("seed-1", "com.example.Build", "build.gradle");
        PlanGraph graph = new PlanGraph(List.of(seed), List.of());
        when(propagation.propagate(any())).thenReturn(graph);
        when(seam.annotate(graph)).thenReturn(graph);
        when(codeGenerator.generateCode(anyString(), anyString(), anyString()))
                .thenReturn(Map.of("build.gradle", "// hostile dependency"));

        EditOrchestrationResult result = orchestrator.orchestrate("proj-1", "req", List.of(seed));

        assertThat(result.nodeErrors())
                .anyMatch(e -> e.contains("sandbox: refusing")
                        && e.contains("build.gradle")
                        && e.contains("JARVIS"));
    }

    @Test
    @DisplayName("sandbox refuses pom.xml and package.json changes too")
    void sandboxRefusesAllBuildManifests() {
        for (String configFile : List.of("pom.xml", "package.json", "package-lock.json")) {
            PlanNode seed = node("seed-1", "com.example.Build", configFile);
            PlanGraph graph = new PlanGraph(List.of(seed), List.of());
            when(propagation.propagate(any())).thenReturn(graph);
            when(seam.annotate(graph)).thenReturn(graph);
            when(codeGenerator.generateCode(anyString(), anyString(), anyString()))
                    .thenReturn(Map.of(configFile, "{}"));

            EditOrchestrationResult result = orchestrator.orchestrate("proj-1", "req", List.of(seed));
            assertThat(result.nodeErrors())
                    .as("config file %s", configFile)
                    .anyMatch(e -> e.contains("sandbox: refusing") && e.contains(configFile));
        }
    }

    private PlanNode node(String id, String fqn, String filePath) {
        return new PlanNode(id, "proj-1", filePath, fqn, "change " + fqn,
                ChangeKind.MODIFY_METHOD_SIGNATURE, false, null);
    }
}
