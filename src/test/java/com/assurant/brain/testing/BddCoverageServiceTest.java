package com.assurant.brain.testing;

import com.assurant.brain.graph.node.BddScenarioNode;
import com.assurant.brain.graph.repository.BddScenarioNodeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("BddCoverageService")
class BddCoverageServiceTest {

    @Test
    @DisplayName("matches automated scenarios to indexed BddScenarioNodes by token overlap")
    void matchesAutomatedScenarios() {
        BddScenarioNodeRepository repo = mock(BddScenarioNodeRepository.class);
        BddScenarioNode existing = scenario("Valid CSV body returns 200 with export ID");
        when(repo.findByCoversProjectId(eq("ce-imei"))).thenReturn(List.of(existing));

        BddCoverageService service = new BddCoverageService(repo);
        List<TestScenario> targets = List.of(
                new TestScenario("TC-1", "Valid CSV body returns 200 export id",
                        TestScenarioType.AUTOMATED, TestScenarioStatus.PENDING, null, null,
                        TestScenarioSource.BRAIN),
                new TestScenario("TC-2", "100k rows in 30 seconds",
                        TestScenarioType.MANUAL, TestScenarioStatus.MANUAL_REQUIRED, null, null,
                        TestScenarioSource.BRAIN));

        BddCoverageReport report = service.reportFor("ce-imei", targets);

        assertThat(report.coveredCount()).isEqualTo(1);
        assertThat(report.gapCount()).isEqualTo(1);
        assertThat(report.uncoveredScenarios()).extracting(TestScenario::scenarioId).containsExactly("TC-2");
    }

    @Test
    @DisplayName("empty indexed list → all targets are gaps")
    void emptyIndexAllGaps() {
        BddScenarioNodeRepository repo = mock(BddScenarioNodeRepository.class);
        when(repo.findByCoversProjectId(eq("p"))).thenReturn(List.of());

        BddCoverageService service = new BddCoverageService(repo);
        List<TestScenario> targets = List.of(
                new TestScenario("TC-A", "anything", TestScenarioType.AUTOMATED,
                        TestScenarioStatus.PENDING, null, null, TestScenarioSource.BRAIN));

        BddCoverageReport report = service.reportFor("p", targets);

        assertThat(report.coveredCount()).isZero();
        assertThat(report.gapCount()).isEqualTo(1);
    }

    private BddScenarioNode scenario(String title) {
        BddScenarioNode n = new BddScenarioNode();
        n.setId("x");
        n.setProjectId("ce-imei-automation");
        n.setCoversProjectId("ce-imei");
        n.setScenarioTitle(title);
        n.setSteps(new ArrayList<>());
        return n;
    }
}
