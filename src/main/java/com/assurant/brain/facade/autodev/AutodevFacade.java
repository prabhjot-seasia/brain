package com.assurant.brain.facade.autodev;

import com.assurant.brain.codegen.PlanGraph;
import com.assurant.brain.codegen.PlanNode;
import com.assurant.brain.enums.ChangeKind;
import com.fasterxml.jackson.databind.JsonNode;
import com.assurant.brain.dto.response.ClarificationResponse;
import com.assurant.brain.dao.ClarificationSessionRepository;
import com.assurant.brain.domain.ClarificationSession;
import com.assurant.brain.dto.request.AutodevCreatePrsRequest;
import com.assurant.brain.dto.response.AutodevExecuteResponse;
import com.assurant.brain.dto.response.AutodevPlanResponse;
import com.assurant.brain.dto.response.AutodevSessionResponse;
import com.assurant.brain.enums.SessionStatus;
import com.assurant.brain.exceptions.SessionNotFoundException;
import com.assurant.brain.intake.IntakeResolver;
import com.assurant.brain.jobs.AsyncJobService;
import com.assurant.brain.service.AffectedProject;
import com.assurant.brain.service.ProjectAffinityDetector;
import com.assurant.brain.service.ClarifierService;
import com.assurant.brain.service.PlannerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Log4j2
@Component
@RequiredArgsConstructor
public class AutodevFacade {

    private final IntakeResolver intakeResolver;
    private final ProjectAffinityDetector affinityDetector;
    private final ClarifierService clarifierService;
    private final PlannerService plannerService;
    private final EditOrchestrator editOrchestrator;
    private final MultiRepoPrOrchestrator multiRepoPrOrchestrator;
    private final ClarificationSessionRepository sessionRepository;
    private final PlanGraphSerializer planGraphSerializer;
    private final ObjectMapper objectMapper;
    private final AsyncJobService asyncJobService;

    @Async("brainLlmExecutor")
    public void executeAsync(String sessionId, List<String> approvedProjectIds, UUID jobId) {
        try {
            asyncJobService.markRunning(jobId, "Executing autodev plan for session " + sessionId);
            AutodevExecuteResponse response = execute(sessionId, approvedProjectIds);
            asyncJobService.markSucceeded(jobId, response);
        } catch (Exception e) {
            log.error("Autodev execute job={} failed: {}", jobId, e.getMessage(), e);
            asyncJobService.markFailed(jobId,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    @Async("brainLlmExecutor")
    public void createPrsAsync(String sessionId, List<AutodevCreatePrsRequest.RepoSpec> repos, UUID jobId) {
        try {
            asyncJobService.markRunning(jobId, "Creating PRs across " + (repos == null ? 0 : repos.size()) + " repo(s)");
            var result = createPrs(sessionId, repos);
            asyncJobService.markSucceeded(jobId, result);
        } catch (Exception e) {
            log.error("Autodev create-prs job={} failed: {}", jobId, e.getMessage(), e);
            asyncJobService.markFailed(jobId,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    @Transactional
    public AutodevSessionResponse start(String source, String payload, String intakeId,
                                          String seedProjectId) {
        String requirement = resolveRequirementText(source, payload, intakeId);

        ClarificationSession session = new ClarificationSession();
        session.setProjectId(seedProjectId == null ? "autodev" : seedProjectId);
        session.setRequirement(requirement);
        session.setRounds(new ArrayList<>());
        session.setStatus(SessionStatus.CLARIFYING);
        session.setAffectedProjects(runAffinityDetection(requirement));
        session = sessionRepository.save(session);

        ClarificationResponse clarification = clarifierService.analyze(
                session.getProjectId(), requirement, session.getRounds());

        List<ClarificationResponse.ClarificationQuestion> questions = new ArrayList<>();
        if (!clarification.isConfident() || !clarification.getUnknownReferences().isEmpty()) {
            questions.addAll(clarification.getQuestions());
            appendRound(session, clarification);
            sessionRepository.save(session);
        }

        return new AutodevSessionResponse(
                session.getId().toString(),
                requirement,
                session.getAffectedProjects(),
                questions,
                questions.isEmpty()
        );
    }

    @Transactional
    public AutodevSessionResponse clarify(String sessionId, String answers) {
        ClarificationSession session = loadSession(sessionId);
        requireStatus(session, "clarify", SessionStatus.PENDING, SessionStatus.CLARIFYING);

        if (answers != null && !answers.isBlank() && !session.getRounds().isEmpty()) {
            Map<String, Object> lastRound = new HashMap<>(session.getRounds().get(session.getRounds().size() - 1));
            lastRound.put("answers", answers);
            List<Map<String, Object>> rounds = new ArrayList<>(session.getRounds());
            rounds.set(rounds.size() - 1, lastRound);
            session.setRounds(rounds);
            sessionRepository.save(session);
        }

        ClarificationResponse clarification = clarifierService.analyze(
                session.getProjectId(), session.getRequirement(), session.getRounds());

        List<ClarificationResponse.ClarificationQuestion> questions = new ArrayList<>();
        if (!clarification.isConfident() || !clarification.getUnknownReferences().isEmpty()) {
            questions.addAll(clarification.getQuestions());
            appendRound(session, clarification);
            sessionRepository.save(session);
        }

        return new AutodevSessionResponse(
                session.getId().toString(),
                session.getRequirement(),
                session.getAffectedProjects(),
                questions,
                questions.isEmpty()
        );
    }

    @Transactional
    public AutodevPlanResponse plan(String sessionId) {
        ClarificationSession session = loadSession(sessionId);
        requireStatus(session, "plan", SessionStatus.PENDING, SessionStatus.CLARIFYING);

        List<String> projectIds = extractProjectIds(session);
        String umbrellaPlan = plannerService.generateMultiRepoPlan(
                projectIds, session.getRequirement(), session.getRounds());

        try {
            Map<String, Object> parsed = objectMapper.readValue(umbrellaPlan, new com.fasterxml.jackson.core.type.TypeReference<>() {});
            session.setFinalPlan(parsed);
            session.setStatus(SessionStatus.PLANNED);
        } catch (Exception parseEx) {
            log.warn("Could not parse multi-repo umbrella plan — storing raw for session={}", sessionId, parseEx);
            session.setFinalPlan(Map.of("raw", umbrellaPlan));
            session.setStatus(SessionStatus.PLANNED);
        }
        sessionRepository.save(session);

        return new AutodevPlanResponse(session.getId().toString(), umbrellaPlan);
    }

    @Transactional
    public AutodevExecuteResponse execute(String sessionId, List<String> approvedProjectIds) {
        ClarificationSession session = loadSession(sessionId);
        requireStatus(session, "execute", SessionStatus.PLANNED, SessionStatus.COMPLETE);
        session.setStatus(SessionStatus.EXECUTING);
        sessionRepository.save(session);

        List<String> projectIds = approvedProjectIds != null && !approvedProjectIds.isEmpty()
                ? approvedProjectIds
                : extractProjectIds(session);

        Map<String, PlanGraph> planGraphByProject = new LinkedHashMap<>();
        Map<String, Map<String, String>> filesByProject = new LinkedHashMap<>();
        List<Map<String, Object>> perProject = new ArrayList<>();

        for (String projectId : projectIds) {
            List<PlanNode> seeds = extractSeedsForProject(session, projectId);
            EditOrchestrationResult result = editOrchestrator.orchestrate(
                    projectId, session.getRequirement(), seeds);

            planGraphByProject.put(projectId, result.planGraph());
            filesByProject.put(projectId, result.files());

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("projectId", projectId);
            summary.put("fileCount", result.files().size());
            summary.put("nodeCount", result.planGraph().size());
            summary.put("nodeErrors", result.nodeErrors());
            perProject.add(summary);
        }

        session.setPlanGraphJson(planGraphSerializer.serialize(planGraphByProject, filesByProject));
        session.setStatus(SessionStatus.EXECUTED);
        sessionRepository.save(session);

        return new AutodevExecuteResponse(session.getId().toString(), perProject);
    }

    public MultiRepoPrResult createPrs(String sessionId, List<AutodevCreatePrsRequest.RepoSpec> repos) {
        ClarificationSession session = loadSession(sessionId);
        requireStatus(session, "create-prs", SessionStatus.EXECUTED, SessionStatus.COMPLETE);
        if (repos == null || repos.isEmpty()) {
            throw new IllegalArgumentException("createPrs requires at least one repo spec");
        }

        Map<String, Map<String, String>> filesByProject = planGraphSerializer.deserializeFiles(session.getPlanGraphJson());
        String planSummary = session.getRequirement();

        List<MultiRepoPrOrchestrator.RepoTarget> targets = new ArrayList<>();
        for (AutodevCreatePrsRequest.RepoSpec spec : repos) {
            Map<String, String> files = filesByProject.getOrDefault(spec.projectId(), Map.of());
            targets.add(new MultiRepoPrOrchestrator.RepoTarget(
                    spec.projectId(), spec.repoUrl(), spec.baseBranch(),
                    files, planSummary));
        }

        String planJson;
        try {
            planJson = objectMapper.writeValueAsString(session.getFinalPlan() == null
                    ? Map.of() : session.getFinalPlan());
        } catch (Exception e) {
            planJson = "{}";
        }

        return multiRepoPrOrchestrator.createBatch(session.getId(), targets, planJson);
    }

    private ClarificationSession loadSession(String sessionId) {
        return sessionRepository.findById(UUID.fromString(sessionId))
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));
    }

    private void requireStatus(ClarificationSession session, String stage, SessionStatus... allowed) {
        SessionStatus current = session.getStatus();
        for (SessionStatus s : allowed) {
            if (current == s) return;
        }
        throw new IllegalStateException(
                "Cannot call /" + stage + " from session status " + current
                        + ". Expected one of: " + java.util.Arrays.toString(allowed));
    }

    private String resolveRequirementText(String source, String payload, String intakeId) {
        if (intakeId != null && !intakeId.isBlank()) {
            return intakeResolver.resolve(UUID.fromString(intakeId));
        }
        if (payload != null && !payload.isBlank()) {
            return payload;
        }
        throw new IllegalArgumentException(
                "Autodev start requires either intakeId (resolved via /intake/*) or a non-empty payload. source=" + source);
    }

    private List<Map<String, Object>> runAffinityDetection(String requirement) {
        try {
            List<AffectedProject> detected = affinityDetector.detect(requirement);
            List<Map<String, Object>> out = new ArrayList<>();
            for (AffectedProject p : detected) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("projectId",  p.projectId());
                entry.put("confidence", p.confidence());
                entry.put("rationale",  p.rationale());
                out.add(entry);
            }
            return out;
        } catch (Exception e) {
            log.warn("AutodevFacade affinity detection failed — single-project fallback: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private void appendRound(ClarificationSession session, ClarificationResponse clarification) {
        List<Map<String, Object>> questionMaps = clarification.getQuestions().stream()
                .map(q -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("text", q.text());
                    m.put("options", q.options());
                    return m;
                })
                .toList();

        Map<String, Object> round = new HashMap<>();
        round.put("questions",         questionMaps);
        round.put("unknownReferences", clarification.getUnknownReferences());
        round.put("answers",           "");
        List<Map<String, Object>> rounds = new ArrayList<>(
                session.getRounds() == null ? List.of() : session.getRounds());
        rounds.add(round);
        session.setRounds(rounds);
    }

    private List<String> extractProjectIds(ClarificationSession session) {
        List<String> ids = new ArrayList<>();
        if (session.getAffectedProjects() != null) {
            for (Map<String, Object> entry : session.getAffectedProjects()) {
                Object pid = entry.get("projectId");
                if (pid != null) ids.add(pid.toString());
            }
        }
        if (ids.isEmpty()) ids.add(session.getProjectId());
        return ids;
    }

    private List<PlanNode> extractSeedsForProject(ClarificationSession session, String projectId) {
        if (session.getFinalPlan() == null) return List.of();
        try {
            JsonNode root = objectMapper.valueToTree(session.getFinalPlan());
            JsonNode projects = root.path("projects");
            if (!projects.isArray()) return List.of();

            for (JsonNode project : projects) {
                if (!projectId.equals(project.path("projectId").asText(""))) continue;
                JsonNode plan = project.path("plan");
                JsonNode files = plan.path("affectedFiles");
                JsonNode steps = plan.path("steps");
                List<PlanNode> seeds = new ArrayList<>();

                if (files.isArray()) {
                    int idx = 0;
                    for (JsonNode f : files) {
                        String path = f.path("path").asText(null);
                        String reason = f.path("reason").asText("");
                        if (path == null || path.isBlank()) continue;
                        seeds.add(new PlanNode(
                                "seed-" + projectId + "-" + (idx++),
                                projectId,
                                path,
                                fileToSymbolGuess(path),
                                reason.isBlank() ? "Edit " + path : reason,
                                ChangeKind.MODIFY_METHOD_BODY,
                                false,
                                null
                        ));
                    }
                }
                if (seeds.isEmpty() && steps.isArray()) {
                    int idx = 0;
                    for (JsonNode step : steps) {
                        String desc = step.path("description").asText("");
                        JsonNode sfiles = step.path("files");
                        String path = sfiles.isArray() && sfiles.size() > 0 ? sfiles.get(0).asText("") : "";
                        if (desc.isBlank()) continue;
                        seeds.add(new PlanNode(
                                "seed-step-" + projectId + "-" + (idx++),
                                projectId,
                                path,
                                fileToSymbolGuess(path),
                                desc,
                                ChangeKind.MODIFY_METHOD_BODY,
                                false,
                                null
                        ));
                    }
                }
                return seeds;
            }
        } catch (Exception e) {
            log.warn("Could not extract seed edits for project={} — returning empty seed list: {}",
                    projectId, e.getMessage());
        }
        return List.of();
    }

    private static String fileToSymbolGuess(String filePath) {
        if (filePath == null || filePath.isBlank()) return null;
        int slash = filePath.lastIndexOf('/');
        String tail = slash >= 0 ? filePath.substring(slash + 1) : filePath;
        int dot = tail.lastIndexOf('.');
        return dot > 0 ? tail.substring(0, dot) : tail;
    }

}
