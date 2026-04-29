package com.assurant.brain.avenger.oracle;

import com.assurant.brain.graph.node.SLONode;
import com.assurant.brain.graph.repository.SLONodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("OraclePromptOptimizer")
class OraclePromptOptimizerTest {

    private SLONodeRepository sloRepo;
    private OraclePromptOptimizer optimizer;

    @BeforeEach
    void setup() {
        sloRepo = mock(SLONodeRepository.class);
        optimizer = new OraclePromptOptimizer(sloRepo);
    }

    @Test
    @DisplayName("99.95% SLO maps to HIGH budget (200/20)")
    void highCriticality() {
        SLONode slo = new SLONode();
        slo.setTargetPercent(99.95);
        when(sloRepo.findByProjectIdOrderByTargetDesc(anyString())).thenReturn(List.of(slo));

        var budget = optimizer.computeBudget("proj-1");

        assertThat(budget.tier()).isEqualTo("HIGH");
        assertThat(budget.recallK()).isEqualTo(200);
        assertThat(budget.rerankK()).isEqualTo(20);
    }

    @Test
    @DisplayName("99.5% SLO maps to MEDIUM budget (100/10)")
    void mediumCriticality() {
        SLONode slo = new SLONode();
        slo.setTargetPercent(99.5);
        when(sloRepo.findByProjectIdOrderByTargetDesc(anyString())).thenReturn(List.of(slo));

        var budget = optimizer.computeBudget("proj-1");

        assertThat(budget.tier()).isEqualTo("MEDIUM");
        assertThat(budget.recallK()).isEqualTo(100);
    }

    @Test
    @DisplayName("99% SLO maps to LOW budget (50/5)")
    void lowCriticality() {
        SLONode slo = new SLONode();
        slo.setTargetPercent(99.0);
        when(sloRepo.findByProjectIdOrderByTargetDesc(anyString())).thenReturn(List.of(slo));

        var budget = optimizer.computeBudget("proj-1");

        assertThat(budget.tier()).isEqualTo("LOW");
        assertThat(budget.recallK()).isEqualTo(50);
    }

    @Test
    @DisplayName("no SLO → DEFAULT budget")
    void defaultWhenNoSlo() {
        when(sloRepo.findByProjectIdOrderByTargetDesc(anyString())).thenReturn(List.of());

        var budget = optimizer.computeBudget("proj-1");

        assertThat(budget.tier()).isEqualTo("DEFAULT");
        assertThat(budget.recallK()).isEqualTo(100);
    }

    @Test
    @DisplayName("blank/null projectId returns DEFAULT without calling repo")
    void blankProjectIdShortCircuitsToDefault() {
        var blank = optimizer.computeBudget("");
        var nul = optimizer.computeBudget(null);

        assertThat(blank.tier()).isEqualTo("DEFAULT");
        assertThat(nul.tier()).isEqualTo("DEFAULT");
        org.mockito.Mockito.verify(sloRepo, org.mockito.Mockito.never())
                .findByProjectIdOrderByTargetDesc(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("repository failure → DEFAULT budget (graceful degrade)")
    void defaultOnFailure() {
        when(sloRepo.findByProjectIdOrderByTargetDesc(anyString()))
                .thenThrow(new RuntimeException("neo4j down"));

        assertThat(optimizer.computeBudget("proj-1").tier()).isEqualTo("DEFAULT");
    }
}
