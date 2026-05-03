package com.assurant.brain.sage;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.sage.dao.ContextGapResolutionRepository;
import com.assurant.brain.sage.domain.ContextGapResolution;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SageInquisitor — context-gap pipeline")
class SageInquisitorIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;

    @Autowired private SageInquisitor sageInquisitor;
    @Autowired private ContextGapResolutionRepository repository;

    @AfterEach
    void cleanup() {
        repository.findByProjectIdAndStatusOrderByCreatedAtAsc("sage-it", GapStatus.PENDING)
                .forEach(g -> repository.deleteById(g.getId()));
        repository.findByProjectIdAndStatusOrderByCreatedAtAsc("sage-it", GapStatus.AUTO_RESOLVED)
                .forEach(g -> repository.deleteById(g.getId()));
        repository.findByProjectIdAndStatusOrderByCreatedAtAsc("sage-it", GapStatus.RESOLVED)
                .forEach(g -> repository.deleteById(g.getId()));
    }

    @Test
    @DisplayName("allowlisted symbols (java.util.*) are auto-resolved, never asked about")
    void allowlistShortCircuits() {
        BrainInputEvent event = new BrainInputEvent(
                BrainInputEventType.REQUIREMENT_ANALYSIS, "sage-it", "tester",
                "uses java.util.List and java.util.Map",
                List.of("java.util.List", "java.util.Map"), java.util.Map.of());

        SageInspectionResult result = sageInquisitor.inspect(event);

        assertThat(result.gapsAutoResolved()).hasSize(2);
        assertThat(result.gapsCreated()).isEmpty();
        assertThat(result.gapsAutoResolved())
                .allMatch(g -> g.getAnswerSource() == AnswerSource.ALLOWLIST);
    }

    @Test
    @DisplayName("non-allowlisted symbol becomes a PENDING Tier 1 gap")
    void unknownSymbolBecomesPending() {
        BrainInputEvent event = new BrainInputEvent(
                BrainInputEventType.REQUIREMENT_ANALYSIS, "sage-it", "tester",
                "uses com.example.MysteryClient",
                List.of("com.example.MysteryClient"), java.util.Map.of());

        SageInspectionResult result = sageInquisitor.inspect(event);

        assertThat(result.gapsCreated()).hasSize(1);
        ContextGapResolution gap = result.gapsCreated().get(0);
        assertThat(gap.getStatus()).isEqualTo(GapStatus.PENDING);
        assertThat(gap.getTier()).isEqualTo((short) 1);
        assertThat(gap.getGapType()).isEqualTo(ContextGapType.SYMBOL_NOT_FOUND);
    }

    @Test
    @DisplayName("readiness report counts open Tier 1 gaps and lists blocking ones")
    void readinessReports() {
        sageInquisitor.inspect(new BrainInputEvent(
                BrainInputEventType.REQUIREMENT_ANALYSIS, "sage-it", null,
                "raw", List.of("com.example.X"), java.util.Map.of()));

        SageInquisitor.ContextReadinessReport report = sageInquisitor.readinessFor("sage-it");

        assertThat(report.tier1Unresolved()).isGreaterThanOrEqualTo(1);
        assertThat(report.blockingGaps()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("resolveAnswer flips status to RESOLVED with HUMAN source")
    void resolveAnswerWorks() {
        SageInspectionResult initial = sageInquisitor.inspect(new BrainInputEvent(
                BrainInputEventType.REQUIREMENT_ANALYSIS, "sage-it", null,
                "raw", List.of("com.example.Y"), java.util.Map.of()));
        ContextGapResolution gap = initial.gapsCreated().get(0);

        sageInquisitor.resolveAnswer("sage-it", gap.getGapSignature(),
                "IN_HOUSE_REPO", "github.com/x/y");

        ContextGapResolution updated = repository.findById(gap.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(GapStatus.RESOLVED);
        assertThat(updated.getAnswerSource()).isEqualTo(AnswerSource.HUMAN);
        assertThat(updated.getAnswerValue()).isEqualTo("github.com/x/y");
    }
}
