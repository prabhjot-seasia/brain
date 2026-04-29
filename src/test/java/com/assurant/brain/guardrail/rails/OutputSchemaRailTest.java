package com.assurant.brain.guardrail.rails;

import com.assurant.brain.enums.RailDecision;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OutputSchemaRail")
class OutputSchemaRailTest {

    private OutputSchemaRail rail;

    @BeforeEach
    void setup() {
        rail = new OutputSchemaRail(new ObjectMapper());
        rail.init();
    }

    @Test
    @DisplayName("passes when no schema metadata provided")
    void passesWithoutSchema() {
        RailResult result = rail.apply(RailContext.postLlm("p", "svc", "{\"x\":1}", Map.of()));
        assertThat(result.decision()).isEqualTo(RailDecision.PASS);
    }

    @Test
    @DisplayName("passes when response matches schema")
    void passesMatchingSchema() {
        String validJson = """
                {
                  "verdict": "PASS",
                  "issues": [],
                  "summary": "Clean"
                }
                """;
        RailResult result = rail.apply(RailContext.postLlm("p", "svc", validJson,
                Map.of(OutputSchemaRail.METADATA_SCHEMA_KEY, "schemas/code-review-result.json")));
        assertThat(result.decision()).isEqualTo(RailDecision.PASS);
    }

    @Test
    @DisplayName("blocks when response violates schema")
    void blocksViolatingSchema() {
        String invalidJson = "{\"verdict\": \"PASS\"}";
        RailResult result = rail.apply(RailContext.postLlm("p", "svc", invalidJson,
                Map.of(OutputSchemaRail.METADATA_SCHEMA_KEY, "schemas/code-review-result.json")));
        assertThat(result.decision()).isEqualTo(RailDecision.BLOCK);
    }

    @Test
    @DisplayName("handles markdown-fenced JSON via LlmJsonParser")
    void handlesFencedJson() {
        String fenced = """
                ```json
                {"verdict":"PASS","issues":[],"summary":"ok"}
                ```
                """;
        RailResult result = rail.apply(RailContext.postLlm("p", "svc", fenced,
                Map.of(OutputSchemaRail.METADATA_SCHEMA_KEY, "schemas/code-review-result.json")));
        assertThat(result.decision()).isEqualTo(RailDecision.PASS);
    }
}
