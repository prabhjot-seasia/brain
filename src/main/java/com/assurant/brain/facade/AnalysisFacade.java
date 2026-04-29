package com.assurant.brain.facade;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.ClarificationSessionRepository;
import com.assurant.brain.domain.ClarificationSession;
import com.assurant.brain.dto.request.AnalyzeRequest;
import com.assurant.brain.dto.response.AnalyzeResponse;
import com.assurant.brain.dto.response.ClarificationResponse;
import com.assurant.brain.enums.SessionStatus;
import com.assurant.brain.exceptions.SessionNotFoundException;
import com.assurant.brain.jobs.AsyncJobService;
import com.assurant.brain.service.AffectedProject;
import com.assurant.brain.service.ClarifierService;
import com.assurant.brain.service.PlannerService;
import com.assurant.brain.service.ProjectAffinityDetector;
import com.assurant.brain.util.LlmJsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Log4j2
@Component("analysisFacade")
@RequiredArgsConstructor
public class AnalysisFacade {

    private static final Pattern EXPLAIN_PATTERN = Pattern.compile(
            "^\\s*(explain|describe|what (is|are|does|do)|how (is|are|does|do)|" +
            "tell me about|show me|summari[sz]e|walk me through|" +
            "give me an overview|what'?s in)\\b",
            Pattern.CASE_INSENSITIVE
    );

    enum RequirementIntent { EXPLAIN, IMPLEMENT }

    private final ClarifierService              clarifierService;
    private final PlannerService                plannerService;
    private final ClarificationSessionRepository sessionRepository;
    private final ObjectMapper                  objectMapper;
    private final ProjectAffinityDetector       projectAffinityDetector;
    private final BrainProperties               brainProperties;
    private final AsyncJobService               asyncJobService;

    @Async("brainLlmExecutor")
    public void analyzeAsync(AnalyzeRequest request, UUID jobId) {
        try {
            asyncJobService.markRunning(jobId,
                    request.sessionId() != null
                            ? "Analyzing answers for session " + request.sessionId()
                            : "Analyzing requirement");
            AnalyzeResponse response = analyze(request);
            asyncJobService.markSucceeded(jobId, response);
        } catch (Exception e) {
            log.error("Analyze job={} failed: {}", jobId, e.getMessage(), e);
            asyncJobService.markFailed(jobId,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    @Transactional
    public AnalyzeResponse analyze(AnalyzeRequest request) {
        List<AffectedProject> cachedAffinity = new ArrayList<>();
        String projectId = resolveProjectId(request, cachedAffinity);
        log.info("Analyzing requirement for project={}, sessionId={}",
                projectId, request.sessionId());

        if (classifyIntent(request.requirement()) == RequirementIntent.EXPLAIN) {
            return handleExplainRequest(new AnalyzeRequest(projectId, request.requirement(), request.sessionId(), request.answers()));
        }

        AnalyzeRequest resolved = new AnalyzeRequest(projectId, request.requirement(), request.sessionId(), request.answers());
        ClarificationSession session = resolveSession(resolved, cachedAffinity);

        if (request.answers() != null && !request.answers().isBlank()) {
            appendAnswers(session, request.answers());
            sessionRepository.save(session);
        }

        ClarificationResponse clarification = clarifierService.analyze(
                projectId,
                request.requirement(),
                session.getRounds()
        );

        boolean stillNeedsClarification =
                !clarification.isConfident() || !clarification.getUnknownReferences().isEmpty();
        int maxRounds = brainProperties.clarifier() != null ? brainProperties.clarifier().maxRounds() : 5;
        boolean reachedMaxRounds = session.getRounds().size() >= maxRounds;

        if (stillNeedsClarification && !reachedMaxRounds) {
            appendQuestions(session, clarification.getQuestions(),
                    clarification.getUnknownReferences());
            sessionRepository.save(session);

            return AnalyzeResponse.needsClarification(
                    session.getId().toString(),
                    clarification.getQuestions(),
                    clarification.getUnknownReferences(),
                    clarification.getDimensions()
            ).withAffectedProjects(session.getAffectedProjects());
        }

        boolean forcedPromote = stillNeedsClarification;
        if (forcedPromote) {
            log.warn("Promoting session={} to planner after {} rounds without confidence — " +
                            "plan will be generated with thinner-than-ideal context",
                    session.getId(), session.getRounds().size());
        }

        String planJson = plannerService.generatePlan(
                projectId,
                request.requirement(),
                session.getRounds()
        );

        session.setStatus(SessionStatus.COMPLETE);
        session.setFinalPlan(parsePlanToMap(planJson));
        sessionRepository.save(session);

        AnalyzeResponse planResponse = forcedPromote
                ? AnalyzeResponse.withForcedPlan(session.getId().toString(), planJson)
                : AnalyzeResponse.withPlan(session.getId().toString(), planJson);
        return planResponse.withAffectedProjects(session.getAffectedProjects());
    }

    private AnalyzeResponse handleExplainRequest(AnalyzeRequest request) {
        log.info("Routing requirement '{}' as EXPLAIN intent (skipping clarifier)",
                request.requirement());

        ClarificationSession session = resolveSession(request, List.of());
        String explanation = plannerService.explainProject(
                request.projectId(), request.requirement());

        session.setStatus(SessionStatus.COMPLETE);
        session.setFinalPlan(Map.of("explanation", explanation));
        sessionRepository.save(session);

        return AnalyzeResponse.withExplanation(session.getId().toString(), explanation);
    }

    RequirementIntent classifyIntent(String requirement) {
        if (requirement == null) return RequirementIntent.IMPLEMENT;
        return EXPLAIN_PATTERN.matcher(requirement).find()
                ? RequirementIntent.EXPLAIN
                : RequirementIntent.IMPLEMENT;
    }

    private String resolveProjectId(AnalyzeRequest request, List<AffectedProject> cachedAffinity) {
        if (request.projectId() != null && !request.projectId().isBlank()) {
            return request.projectId();
        }
        if (request.sessionId() != null) {
            return sessionRepository.findById(UUID.fromString(request.sessionId()))
                    .map(ClarificationSession::getProjectId)
                    .orElseThrow(() -> new SessionNotFoundException("Session not found: " + request.sessionId()));
        }
        List<AffectedProject> detected = projectAffinityDetector.detect(request.requirement());
        if (detected.isEmpty()) {
            throw new IllegalArgumentException("No matching projects found for this requirement. Ingest a project first.");
        }
        cachedAffinity.addAll(detected);
        log.info("Auto-detected project={} (confidence={}) for requirement",
                detected.get(0).projectId(), detected.get(0).confidence());
        return detected.get(0).projectId();
    }

    private ClarificationSession resolveSession(AnalyzeRequest request, List<AffectedProject> cachedAffinity) {
        if (request.sessionId() != null) {
            return sessionRepository.findById(UUID.fromString(request.sessionId()))
                    .orElseThrow(() -> new SessionNotFoundException(
                            "Session not found: " + request.sessionId()));
        }
        ClarificationSession session = new ClarificationSession();
        session.setProjectId(request.projectId());
        session.setRequirement(request.requirement());
        session.setRounds(new ArrayList<>());
        session.setAffectedProjects(cachedAffinity.isEmpty()
                ? runAffinityDetection(request.requirement())
                : serializeAffinity(cachedAffinity));
        return sessionRepository.save(session);
    }

    private List<Map<String, Object>> serializeAffinity(List<AffectedProject> detected) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (AffectedProject p : detected) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("projectId",  p.projectId());
            entry.put("confidence", p.confidence());
            entry.put("rationale",  p.rationale());
            serialized.add(entry);
        }
        return serialized;
    }

    private List<Map<String, Object>> runAffinityDetection(String requirement) {
        try {
            List<AffectedProject> detected = projectAffinityDetector.detect(requirement);
            List<Map<String, Object>> serialized = new ArrayList<>();
            for (AffectedProject p : detected) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("projectId",  p.projectId());
                entry.put("confidence", p.confidence());
                entry.put("rationale",  p.rationale());
                serialized.add(entry);
            }
            log.info("Affinity detection proposed {} affected project(s)", serialized.size());
            return serialized;
        } catch (Exception e) {
            log.warn("Affinity detection failed — session will fall back to single-repo flow: {}",
                    e.getMessage());
            return List.of();
        }
    }

    private void appendAnswers(ClarificationSession session, String answers) {
        List<Map<String, Object>> existing = session.getRounds();
        if (existing == null || existing.isEmpty()) {
            log.warn("appendAnswers called on session={} with no rounds — answers dropped",
                    session.getId());
            return;
        }
        List<Map<String, Object>> rounds = new ArrayList<>(existing);
        Map<String, Object> lastRound = new HashMap<>(rounds.get(rounds.size() - 1));
        lastRound.put("answers", answers);
        rounds.set(rounds.size() - 1, lastRound);
        session.setRounds(rounds);
    }

    private void appendQuestions(ClarificationSession session, List<ClarificationResponse.ClarificationQuestion> questions,
                                  List<String> unknownReferences) {
        List<Map<String, Object>> questionMaps = questions.stream()
                .map(q -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("text", q.text());
                    m.put("options", q.options());
                    return m;
                })
                .toList();

        Map<String, Object> round = new HashMap<>();
        round.put("questions",         questionMaps);
        round.put("unknownReferences", unknownReferences);
        round.put("answers",           "");
        List<Map<String, Object>> rounds = new ArrayList<>(
                session.getRounds() == null ? List.of() : session.getRounds());
        rounds.add(round);
        session.setRounds(rounds);
    }

    private Map<String, Object> parsePlanToMap(String planJson) {
        try {
            String json = LlmJsonParser.stripFences(planJson);
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (com.fasterxml.jackson.core.io.JsonEOFException eof) {
            log.error("Plan JSON was truncated mid-output (got {} chars before EOF). The LLM " +
                            "hit its output token limit. For Ollama, raise " +
                            "spring.ai.ollama.chat.options.num-predict; for Anthropic, raise " +
                            "spring.ai.anthropic.chat.options.max-tokens. Storing raw string for inspection.",
                    planJson.length(), eof);
            return Map.of("raw", planJson, "error", "TRUNCATED_OUTPUT", "errorDetail", eof.getMessage());
        } catch (Exception e) {
            log.warn("Could not parse plan JSON for persistence — storing as raw string wrapper", e);
            return Map.of("raw", planJson);
        }
    }
}
