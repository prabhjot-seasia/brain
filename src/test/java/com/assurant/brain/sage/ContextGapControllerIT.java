package com.assurant.brain.sage;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.sage.dao.ContextGapResolutionRepository;
import com.assurant.brain.sage.domain.ContextGapResolution;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("ContextGapController — REST surface for SAGE")
class ContextGapControllerIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;

    @Autowired private ContextGapResolutionRepository repository;
    @Autowired private ObjectMapper objectMapper;

    @AfterEach
    void cleanup() {
        repository.deleteAll();
    }

    private ContextGapResolution seed(String projectId, ContextGapType type, GapStatus status) {
        ContextGapResolution r = new ContextGapResolution();
        r.setProjectId(projectId);
        r.setGapSignature("sig-" + java.util.UUID.randomUUID());
        r.setGapType(type);
        r.setTier((short) type.defaultTier());
        r.setStatus(status);
        r.setQuestion("Question for " + type.name());
        r.setConfidence(BigDecimal.valueOf(0.85));
        return repository.save(r);
    }

    @Test
    @DisplayName("GET /context-gaps lists open (PENDING + DEFERRED) only")
    void listsOpenGaps() throws Exception {
        seed("p1", ContextGapType.SYMBOL_NOT_FOUND, GapStatus.PENDING);
        seed("p1", ContextGapType.LIBRARY_CHOICE, GapStatus.RESOLVED);

        mvc.perform(get("/api/v1/projects/{p}/context-gaps", "p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
    }

    @Test
    @DisplayName("POST /resolve flips status to RESOLVED")
    void resolvesGap() throws Exception {
        ContextGapResolution gap = seed("p1", ContextGapType.SYMBOL_NOT_FOUND, GapStatus.PENDING);
        String body = objectMapper.writeValueAsString(Map.of(
                "gapSignature", gap.getGapSignature(),
                "answerKind", "IN_HOUSE_REPO",
                "answerValue", "github.com/x/y"));

        mvc.perform(post("/api/v1/projects/{p}/context-gaps/resolve", "p1")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.answerSource").value("HUMAN"));

        ContextGapResolution updated = repository.findById(gap.getId()).orElseThrow();
        assertThat(updated.getAnswerValue()).isEqualTo("github.com/x/y");
    }

    @Test
    @DisplayName("GET /readiness summarizes Tier 1 unresolved count")
    void readinessSummary() throws Exception {
        seed("p1", ContextGapType.SYMBOL_NOT_FOUND, GapStatus.PENDING);
        seed("p1", ContextGapType.LIBRARY_CHOICE, GapStatus.PENDING);

        mvc.perform(get("/api/v1/projects/{p}/context-gaps/readiness", "p1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier1Unresolved").value(1))
                .andExpect(jsonPath("$.tier3Unresolved").value(1));
    }

    @Test
    @DisplayName("POST /resolve unknown gap → 404")
    void resolveUnknownGap() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "gapSignature", "ghost-signature",
                "answerKind", "FREE_TEXT",
                "answerValue", "?"));
        mvc.perform(post("/api/v1/projects/{p}/context-gaps/resolve", "p1")
                        .contentType("application/json").content(body))
                .andExpect(status().isNotFound());
    }
}
