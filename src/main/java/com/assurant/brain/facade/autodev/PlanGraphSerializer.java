package com.assurant.brain.facade.autodev;

import com.assurant.brain.codegen.PlanGraph;
import com.assurant.brain.codegen.PlanNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Log4j2
@Component
@RequiredArgsConstructor
public class PlanGraphSerializer {

    private final ObjectMapper objectMapper;

    public Map<String, Object> serialize(Map<String, PlanGraph> graphsByProject,
                                          Map<String, Map<String, String>> filesByProject) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("graphs", serializeGraphs(graphsByProject));
        out.put("generatedFiles", filesByProject);
        return out;
    }

    public Map<String, Map<String, String>> deserializeFiles(Map<String, Object> planGraphJson) {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        if (planGraphJson == null) return out;
        try {
            JsonNode root = objectMapper.valueToTree(planGraphJson);
            JsonNode files = root.path("generatedFiles");
            if (!files.isObject()) return out;
            files.fields().forEachRemaining(entry -> {
                Map<String, String> perFile = new LinkedHashMap<>();
                entry.getValue().fields().forEachRemaining(f ->
                        perFile.put(f.getKey(), f.getValue().asText("")));
                out.put(entry.getKey(), perFile);
            });
        } catch (Exception e) {
            log.warn("Could not deserialize generated files from plan_graph_json: {}", e.getMessage());
        }
        return out;
    }

    private Map<String, Object> serializeGraphs(Map<String, PlanGraph> graphs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, PlanGraph> entry : graphs.entrySet()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("nodes", entry.getValue().nodes().stream().map(this::serializeNode).toList());
            g.put("edges", entry.getValue().edges().stream().map(e -> Map.of(
                    "from", e.from(),
                    "to", e.to(),
                    "relation", e.relation()
            )).toList());
            out.put(entry.getKey(), g);
        }
        return out;
    }

    private Map<String, Object> serializeNode(PlanNode n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blockId", safe(n.blockId()));
        m.put("projectId", safe(n.projectId()));
        m.put("filePath", safe(n.filePath()));
        m.put("targetSymbol", safe(n.targetSymbol()));
        m.put("instruction", safe(n.instruction()));
        m.put("kind", n.kind() == null ? "UNKNOWN" : n.kind().name());
        m.put("derived", n.derived());
        m.put("seamFlag", n.seamFlag() == null ? "UNKNOWN" : n.seamFlag().name());
        return m;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
