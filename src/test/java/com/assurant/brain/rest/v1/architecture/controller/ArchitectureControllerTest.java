package com.assurant.brain.rest.v1.architecture.controller;

import com.assurant.brain.graph.node.EndpointNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.QueueNode;
import com.assurant.brain.graph.node.ServiceNode;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ArchitectureController")
class ArchitectureControllerTest {

    private ProjectNodeRepository projectRepo;
    private com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeRepo;
    private ArchitectureController controller;

    @BeforeEach
    void setup() {
        projectRepo = mock(ProjectNodeRepository.class);
        runtimeRepo = mock(com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository.class);
        when(runtimeRepo.findByProjectIdOrderByFrequencyDesc(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(java.util.List.of());
        controller = new ArchitectureController(projectRepo, runtimeRepo);
    }

    @Test
    @DisplayName("returns 404 when project not found")
    void notFound() {
        when(projectRepo.findById("missing")).thenReturn(Optional.empty());
        var resp = controller.getArchitecture("missing");
        assertThat(resp.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("returns ArchitectureView with empty edge lists when project has no relations")
    void emptyEdges() {
        ProjectNode p = new ProjectNode();
        p.setId("ce-imei");
        p.setName("CE IMEI");
        p.setKind("APPLICATION");
        when(projectRepo.findById("ce-imei")).thenReturn(Optional.of(p));

        var resp = controller.getArchitecture("ce-imei");
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        var view = resp.getBody();
        assertThat(view.projectId()).isEqualTo("ce-imei");
        assertThat(view.projectName()).isEqualTo("CE IMEI");
        assertThat(view.kind()).isEqualTo("APPLICATION");
        assertThat(view.calledServices()).isEmpty();
        assertThat(view.publishesTo()).isEmpty();
        assertThat(view.consumesFrom()).isEmpty();
        assertThat(view.exposedEndpoints()).isEmpty();
        assertThat(view.counts()).containsEntry("calledServices", 0)
                .containsEntry("publishesTo", 0)
                .containsEntry("consumesFrom", 0)
                .containsEntry("endpoints", 0);
    }

    @Test
    @DisplayName("includes runtime edges from RuntimeServiceEdgeNodeRepository")
    void runtimeEdgesIncluded() {
        ProjectNode p = new ProjectNode();
        p.setId("ce-imei");
        when(projectRepo.findById("ce-imei")).thenReturn(Optional.of(p));

        com.assurant.brain.graph.node.RuntimeServiceEdgeNode rt =
                new com.assurant.brain.graph.node.RuntimeServiceEdgeNode();
        rt.setFromServiceName("ce-imei");
        rt.setToServiceName("promoter");
        rt.setFrequency(1234L);
        rt.setP50LatencyMs(12.5);
        rt.setP99LatencyMs(85.0);
        rt.setErrorRate(0.012);
        rt.setSource("xray");
        rt.setSampledFromHours(168);
        when(runtimeRepo.findByProjectIdOrderByFrequencyDesc("ce-imei")).thenReturn(List.of(rt));

        var view = controller.getArchitecture("ce-imei").getBody();
        assertThat(view.runtimeEdges()).hasSize(1);
        var edge = view.runtimeEdges().get(0);
        assertThat(edge.fromService()).isEqualTo("ce-imei");
        assertThat(edge.toService()).isEqualTo("promoter");
        assertThat(edge.frequency()).isEqualTo(1234L);
        assertThat(edge.errorRate()).isEqualTo(0.012);
        assertThat(view.counts()).containsEntry("runtimeEdges", 1);
    }

    @Test
    @DisplayName("aggregates services, queues, and endpoints into typed edges")
    void populatedEdges() {
        ProjectNode p = new ProjectNode();
        p.setId("ce-imei");
        p.setName("CE IMEI");
        p.setKind("APPLICATION");

        ServiceNode svc = new ServiceNode();
        svc.setId("svc-promoter");
        svc.setName("promoter");
        svc.setBaseUrlTemplate("https://promoter/${env}");
        svc.setInferredProjectId("ce-promoter");
        svc.setSource("yaml");

        QueueNode pubQ = new QueueNode();
        pubQ.setId("q-out");
        pubQ.setQueueType("SQS");
        pubQ.setName("orders");
        pubQ.setArn("arn:aws:sqs:::orders");
        pubQ.setSource("yaml");

        QueueNode subQ = new QueueNode();
        subQ.setId("q-in");
        subQ.setQueueType("KAFKA");
        subQ.setName("events");
        subQ.setSource("annotation");

        EndpointNode ep = new EndpointNode();
        ep.setId("ep-1");
        ep.setPath("/api/v1/things");
        ep.setHttpMethod("GET");
        ep.setSource("controller");

        p.setCalledServices(List.of(svc));
        p.setPublishedQueues(List.of(pubQ));
        p.setConsumedQueues(List.of(subQ));
        p.setExposedEndpoints(List.of(ep));
        when(projectRepo.findById("ce-imei")).thenReturn(Optional.of(p));

        var view = controller.getArchitecture("ce-imei").getBody();
        assertThat(view.calledServices()).extracting(ArchitectureController.ServiceEdge::name)
                .containsExactly("promoter");
        assertThat(view.publishesTo()).extracting(ArchitectureController.QueueEdge::queueType)
                .containsExactly("SQS");
        assertThat(view.consumesFrom()).extracting(ArchitectureController.QueueEdge::queueType)
                .containsExactly("KAFKA");
        assertThat(view.exposedEndpoints()).extracting(ArchitectureController.EndpointSummary::path)
                .containsExactly("/api/v1/things");
        assertThat(view.counts()).containsEntry("calledServices", 1)
                .containsEntry("publishesTo", 1)
                .containsEntry("consumesFrom", 1)
                .containsEntry("endpoints", 1);
    }
}
