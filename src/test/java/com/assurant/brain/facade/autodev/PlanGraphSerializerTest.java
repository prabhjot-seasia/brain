package com.assurant.brain.facade.autodev;

import com.assurant.brain.codegen.PlanEdge;
import com.assurant.brain.codegen.PlanGraph;
import com.assurant.brain.codegen.PlanNode;
import com.assurant.brain.enums.ChangeKind;
import com.assurant.brain.enums.SeamFlag;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PlanGraphSerializer")
class PlanGraphSerializerTest {

    private PlanGraphSerializer serializer;

    @BeforeEach
    void setup() {
        serializer = new PlanGraphSerializer(new ObjectMapper());
    }

    @Test
    @DisplayName("serialize emits graphs map with nodes/edges and a generatedFiles map")
    void serializeShape() {
        PlanNode node = new PlanNode("n1", "ce-imei", "src/Foo.java", "com.example.Foo",
                "rename", ChangeKind.MODIFY_METHOD_SIGNATURE, false, SeamFlag.SAFE_SEAM);
        PlanEdge edge = new PlanEdge("n1", "n2", "CALLS");
        PlanGraph g = new PlanGraph(List.of(node), List.of(edge));
        Map<String, Object> out = serializer.serialize(
                Map.of("ce-imei", g),
                Map.of("ce-imei", Map.of("src/Foo.java", "class Foo {}")));

        assertThat(out).containsKeys("graphs", "generatedFiles");
        @SuppressWarnings("unchecked")
        Map<String, Object> graphs = (Map<String, Object>) out.get("graphs");
        assertThat(graphs).containsKey("ce-imei");
        @SuppressWarnings("unchecked")
        Map<String, Object> perProject = (Map<String, Object>) graphs.get("ce-imei");
        assertThat(perProject).containsKeys("nodes", "edges");
    }

    @Test
    @DisplayName("serialize handles null kind/seamFlag with UNKNOWN fallback")
    void serializeNullEnumsFallback() {
        PlanNode node = new PlanNode("n1", "p", "Foo.java", "Foo", "x", null, true, null);
        PlanGraph g = new PlanGraph(List.of(node), List.of());
        Map<String, Object> out = serializer.serialize(
                Map.of("p", g), Map.of());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>) out.get("graphs")).get("p")).get("nodes");
        assertThat(nodes).hasSize(1);
        Map<String, Object> n = nodes.get(0);
        assertThat(n).containsEntry("kind", "UNKNOWN")
                .containsEntry("seamFlag", "UNKNOWN")
                .containsEntry("derived", true);
    }

    @Test
    @DisplayName("serialize replaces null string fields with empty string (safe())")
    void serializeNullStringsSafe() {
        PlanNode node = new PlanNode(null, null, null, null, null,
                ChangeKind.ADD_CLASS, false, SeamFlag.SAFE_SEAM);
        PlanGraph g = new PlanGraph(List.of(node), List.of());
        Map<String, Object> out = serializer.serialize(Map.of("p", g), Map.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> n = (Map<String, Object>) ((List<?>) ((Map<String, Object>) ((Map<String, Object>) out.get("graphs")).get("p")).get("nodes")).get(0);
        assertThat(n).containsEntry("blockId", "")
                .containsEntry("projectId", "")
                .containsEntry("filePath", "")
                .containsEntry("targetSymbol", "")
                .containsEntry("instruction", "");
    }

    @Test
    @DisplayName("deserializeFiles roundtrips serialize output")
    void deserializeFilesRoundtrip() {
        Map<String, Object> serialized = serializer.serialize(
                Map.of(),
                Map.of("ce-imei", Map.of("src/A.java", "class A {}", "src/B.java", "class B {}")));

        Map<String, Map<String, String>> back = serializer.deserializeFiles(serialized);
        assertThat(back).containsKey("ce-imei");
        assertThat(back.get("ce-imei"))
                .containsEntry("src/A.java", "class A {}")
                .containsEntry("src/B.java", "class B {}");
    }

    @Test
    @DisplayName("deserializeFiles returns empty map when input is null")
    void deserializeFilesNullInput() {
        assertThat(serializer.deserializeFiles(null)).isEmpty();
    }

    @Test
    @DisplayName("deserializeFiles returns empty map when generatedFiles is missing")
    void deserializeFilesNoKey() {
        assertThat(serializer.deserializeFiles(Map.of("graphs", Map.of()))).isEmpty();
    }

    @Test
    @DisplayName("deserializeFiles returns empty map when generatedFiles is not an object")
    void deserializeFilesNonObjectGeneratedFiles() {
        assertThat(serializer.deserializeFiles(Map.of("generatedFiles", "not-a-map"))).isEmpty();
    }

    @Test
    @DisplayName("serialize edge map carries from/to/relation")
    void serializeEdgeShape() {
        PlanGraph g = new PlanGraph(List.of(),
                List.of(new PlanEdge("a", "b", "CALLS"),
                        new PlanEdge("b", "c", "READS_FROM")));
        Map<String, Object> out = serializer.serialize(Map.of("p", g), Map.of());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) ((Map<String, Object>) ((Map<String, Object>) out.get("graphs")).get("p")).get("edges");
        assertThat(edges).hasSize(2);
        Map<String, Object> first = edges.get(0);
        assertThat(first).containsEntry("from", "a")
                .containsEntry("to", "b")
                .containsEntry("relation", "CALLS");
    }
}
