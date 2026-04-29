package com.assurant.brain.service;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.guardrail.RailChain;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.rails.OutputSchemaRail;
import com.assurant.brain.monitor.TokenUsageTracker;
import com.assurant.brain.util.LlmJsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Log4j2
@Service
@RequiredArgsConstructor
public class ProjectAffinityDetector {

    private static final String AFFECTED_PROJECTS_SCHEMA = "schemas/affected-projects.json";

    private static final String SYSTEM_PROMPT = """
            You are the Project Brain's affinity detector. Given a development requirement and the list
            of projects indexed in the Brain (with their language, framework, and declared dependencies),
            identify which projects are likely affected by the requirement.

            For each affected project, return:
              - projectId: exact ID from the known-projects list
              - confidence: 0.0 to 1.0
                  * 1.0 = requirement explicitly names this project or its components
                  * 0.7+ = strong signal from language/framework/domain alignment
                  * 0.5 = plausible but not clearly required
                  * below 0.5 = weak signal; include only if you must
              - rationale: one-line reason you picked this project

            Rules:
              1. Only reference projects that exist in the known-projects list. NEVER invent project IDs.
              2. If the requirement mentions a system/service not in the list, omit it; the clarifier will ask.
              3. Prefer fewer, more-confident matches over broad low-confidence sprays.
              4. If declared dependencies connect projects (A DEPENDS_ON B) and the requirement affects A's
                 API, consider whether B needs a change too.

            Return a JSON object:
            {
              "affectedProjects": [{"projectId": "...", "confidence": 0.0-1.0, "rationale": "..."}],
              "reasoning": "one-paragraph summary of your analysis"
            }

            Return ONLY valid JSON — no markdown fences, no explanation outside the JSON.
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final ProjectNodeRepository projectNodeRepository;
    private final TokenUsageTracker tokenUsageTracker;
    private final BrainProperties brainProperties;
    private final RailChain railChain;

    public List<AffectedProject> detect(String requirement) {
        List<ProjectNode> projects = projectNodeRepository.findAll();
        if (projects.isEmpty()) {
            log.warn("No projects in registry — affinity detector returning empty list");
            return List.of();
        }

        RailChain.ChainResult preLlm = railChain.applyPreLlm(
                RailContext.preLlm(null, "ProjectAffinityDetector", requirement));
        String sanitizedRequirement = preLlm.sanitized();

        String knownProjects = formatProjectRegistry(projects);

        Prompt prompt = new Prompt(List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage("""
                        Known projects (from the Brain registry):
                        %s

                        Requirement:
                        %s

                        Identify affected projects.
                        """.formatted(knownProjects, sanitizedRequirement))
        ));

        long startMs = System.currentTimeMillis();
        String rawResponse = chatModel.call(prompt).getResult().getOutput().getText();
        long latencyMs = System.currentTimeMillis() - startMs;

        String modelName = brainProperties.llm() != null ? brainProperties.llm().extractModel() : "unknown";
        tokenUsageTracker.track("ProjectAffinityDetector", LlmOperation.MULTI_REPO_DETECT, null,
                sanitizedRequirement, rawResponse, latencyMs, false, modelName);

        railChain.applyPostLlm(RailContext.postLlm(null, "ProjectAffinityDetector", rawResponse,
                Map.of(OutputSchemaRail.METADATA_SCHEMA_KEY, AFFECTED_PROJECTS_SCHEMA)));

        return parseResponse(rawResponse, projects);
    }

    private String formatProjectRegistry(List<ProjectNode> projects) {
        StringBuilder sb = new StringBuilder();
        for (ProjectNode p : projects) {
            sb.append("- ").append(p.getId())
                    .append(" (language=").append(nullToDash(p.getLanguage()))
                    .append(", framework=").append(nullToDash(p.getFramework()))
                    .append(")");
            if (p.getDependencies() != null && !p.getDependencies().isEmpty()) {
                sb.append(" depends on: ");
                sb.append(p.getDependencies().stream().map(ProjectNode::getId).reduce((a, b) -> a + ", " + b).orElse(""));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String nullToDash(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }

    private List<AffectedProject> parseResponse(String rawJson, List<ProjectNode> knownProjects) {
        String json = LlmJsonParser.stripFences(rawJson);
        List<AffectedProject> results = new ArrayList<>();
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> list = (List<Map<String, Object>>) parsed.getOrDefault("affectedProjects", List.of());
            java.util.Set<String> knownIds = new java.util.HashSet<>();
            knownProjects.forEach(p -> knownIds.add(p.getId()));
            for (Map<String, Object> entry : list) {
                String projectId = (String) entry.get("projectId");
                if (projectId == null || !knownIds.contains(projectId)) {
                    log.warn("Affinity detector referenced unknown project '{}' — dropping", projectId);
                    continue;
                }
                double confidence = ((Number) entry.getOrDefault("confidence", 0.0)).doubleValue();
                String rationale = (String) entry.getOrDefault("rationale", "");
                results.add(new AffectedProject(projectId, confidence, rationale));
            }
        } catch (Exception e) {
            log.error("Failed to parse affinity detector response: {}", e.getMessage());
        }
        return results;
    }
}
