package com.assurant.brain.docs;

import com.assurant.brain.graph.repository.EndpointSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.List;

@Log4j2
@Service
@RequiredArgsConstructor
public class RealEndpointRegistry {

    private final EndpointSummaryRepository repository;

    public List<EndpointSummary> listFor(String projectId) {
        if (projectId == null || projectId.isBlank()) return List.of();
        try {
            List<EndpointSummary> result = repository.findControllersByProjectId(projectId).stream()
                    .map(row -> new EndpointSummary(
                            String.valueOf(row.get("qualifiedName")),
                            String.valueOf(row.get("filePath"))))
                    .filter(e -> e.qualifiedName() != null && !e.qualifiedName().isBlank())
                    .toList();
            log.info("RealEndpointRegistry: project={} found {} controllers: {}",
                    projectId, result.size(),
                    result.stream().map(EndpointSummary::qualifiedName).toList());
            return result;
        } catch (RuntimeException e) {
            log.warn("RealEndpointRegistry.listFor({}) failed: {}", projectId, e.toString());
            return List.of();
        }
    }

    public String formatForPrompt(String projectId) {
        List<EndpointSummary> endpoints = listFor(projectId);
        if (endpoints.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("REAL @RestController CLASSES IN THIS PROJECT (use these, do NOT invent others):\n");
        for (EndpointSummary e : endpoints) {
            sb.append("- ").append(e.qualifiedName()).append("\n");
        }
        return sb.toString();
    }

}
