package com.assurant.brain.guardrail.rails;

import com.assurant.brain.enums.RailDecision;
import com.assurant.brain.graph.node.IncidentNode;
import com.assurant.brain.graph.repository.IncidentNodeRepository;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ServiceHealthRail")
class ServiceHealthRailTest {

    private IncidentNodeRepository incidentRepo;
    private ServiceHealthRail rail;

    @BeforeEach
    void setup() {
        incidentRepo = mock(IncidentNodeRepository.class);
        rail = new ServiceHealthRail(incidentRepo);
    }

    @Test
    @DisplayName("passes when projectId is missing (no scoping)")
    void passesWithoutProject() {
        RailContext ctx = RailContext.preLlm(null, "Planner", "input",
                Map.of(ServiceHealthRail.METADATA_AFFECTED_SERVICES, List.of("svc")));
        assertThat(rail.apply(ctx).decision()).isEqualTo(RailDecision.PASS);
    }

    @Test
    @DisplayName("passes when no affectedServices metadata supplied")
    void passesWithoutAffectedServicesMetadata() {
        RailContext ctx = RailContext.preLlm("p", "Planner", "input", Map.of());
        assertThat(rail.apply(ctx).decision()).isEqualTo(RailDecision.PASS);
    }

    @Test
    @DisplayName("BLOCKs when an affected service has an OPEN incident")
    void blocksOnOpenIncident() {
        IncidentNode i = new IncidentNode();
        i.setTitle("OrderService NPE on retry");
        i.setSeverity("SEV2");
        i.setStatus("OPEN");
        when(incidentRepo.findByProjectIdAndClassHintMatch(anyString(), anyString()))
                .thenReturn(List.of(i));

        RailContext ctx = RailContext.preLlm("p", "Planner", "Modify OrderService",
                Map.of(ServiceHealthRail.METADATA_AFFECTED_SERVICES, List.of("OrderService")));

        RailResult result = rail.apply(ctx);

        assertThat(result.decision()).isEqualTo(RailDecision.BLOCK);
        assertThat(result.violations()).anyMatch(v -> v.contains("OrderService NPE on retry"));
        assertThat(result.violations()).anyMatch(v -> v.contains("SEV2"));
    }

    @Test
    @DisplayName("passes when matching incident has RESOLVED status")
    void passesOnResolvedIncident() {
        IncidentNode i = new IncidentNode();
        i.setTitle("Old issue");
        i.setStatus("RESOLVED");
        when(incidentRepo.findByProjectIdAndClassHintMatch(anyString(), anyString()))
                .thenReturn(List.of(i));

        RailContext ctx = RailContext.preLlm("p", "Planner", "Modify OrderService",
                Map.of(ServiceHealthRail.METADATA_AFFECTED_SERVICES, List.of("OrderService")));

        assertThat(rail.apply(ctx).decision()).isEqualTo(RailDecision.PASS);
    }

    @Test
    @DisplayName("graceful pass when repository throws")
    void gracefulOnRepoFailure() {
        when(incidentRepo.findByProjectIdAndClassHintMatch(anyString(), anyString()))
                .thenThrow(new RuntimeException("neo4j down"));

        RailContext ctx = RailContext.preLlm("p", "Planner", "Modify OrderService",
                Map.of(ServiceHealthRail.METADATA_AFFECTED_SERVICES, List.of("OrderService")));

        assertThat(rail.apply(ctx).decision()).isEqualTo(RailDecision.PASS);
    }
}
