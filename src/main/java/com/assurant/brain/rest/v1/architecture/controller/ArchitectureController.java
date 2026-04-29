package com.assurant.brain.rest.v1.architecture.controller;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.RuntimeServiceEdgeNode;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Log4j2
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1")
public class ArchitectureController {

    private final ProjectNodeRepository projectNodeRepository;
    private final RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;

    @GetMapping(value = "/projects/{projectId}/architecture",
                produces = MediaType.APPLICATION_JSON_VALUE)
    @org.springframework.security.access.prepost.PreAuthorize("@projectAccess.canRead(#projectId)")
    public ResponseEntity<ArchitectureView> getArchitecture(@PathVariable String projectId) {
        log.info("Architecture request for project={}", projectId);

        Optional<ProjectNode> maybeProject = projectNodeRepository.findById(projectId);
        if (maybeProject.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        ProjectNode project = maybeProject.get();

        List<ServiceEdge> calledServices = project.getCalledServices().stream()
                .map(s -> new ServiceEdge(s.getId(), s.getName(),
                        s.getBaseUrlTemplate(), s.getInferredProjectId(), s.getSource()))
                .toList();

        List<QueueEdge> publishesTo = project.getPublishedQueues().stream()
                .map(q -> new QueueEdge(q.getId(), q.getQueueType(), q.getName(), q.getArn(), q.getSource()))
                .toList();

        List<QueueEdge> consumesFrom = project.getConsumedQueues().stream()
                .map(q -> new QueueEdge(q.getId(), q.getQueueType(), q.getName(), q.getArn(), q.getSource()))
                .toList();

        List<EndpointSummary> endpoints = project.getExposedEndpoints().stream()
                .map(e -> new EndpointSummary(e.getId(), e.getPath(), e.getHttpMethod(), e.getSource()))
                .toList();

        List<RuntimeEdge> runtimeEdges = runtimeServiceEdgeNodeRepository
                .findByProjectIdOrderByFrequencyDesc(projectId).stream()
                .map(this::toRuntimeEdge)
                .toList();

        ArchitectureView view = new ArchitectureView(
                project.getId(),
                project.getName(),
                project.getKind(),
                calledServices,
                publishesTo,
                consumesFrom,
                endpoints,
                runtimeEdges,
                Map.of(
                        "calledServices", calledServices.size(),
                        "publishesTo", publishesTo.size(),
                        "consumesFrom", consumesFrom.size(),
                        "endpoints", endpoints.size(),
                        "runtimeEdges", runtimeEdges.size()));

        return ResponseEntity.ok(view);
    }

    private RuntimeEdge toRuntimeEdge(RuntimeServiceEdgeNode e) {
        return new RuntimeEdge(e.getFromServiceName(), e.getToServiceName(),
                e.getFrequency(), e.getP50LatencyMs(), e.getP99LatencyMs(),
                e.getErrorRate(), e.getSource(), e.getSampledFromHours());
    }

    public record ArchitectureView(
            String projectId,
            String projectName,
            String kind,
            List<ServiceEdge> calledServices,
            List<QueueEdge> publishesTo,
            List<QueueEdge> consumesFrom,
            List<EndpointSummary> exposedEndpoints,
            List<RuntimeEdge> runtimeEdges,
            Map<String, Integer> counts) {}

    public record RuntimeEdge(String fromService, String toService,
                               long frequency, double p50LatencyMs, double p99LatencyMs,
                               double errorRate, String source, int sampledFromHours) {}

    public record ServiceEdge(String id, String name, String baseUrlTemplate,
                               String inferredProjectId, String source) {}

    public record QueueEdge(String id, String queueType, String name, String arn, String source) {}

    public record EndpointSummary(String id, String path, String httpMethod, String source) {}
}
