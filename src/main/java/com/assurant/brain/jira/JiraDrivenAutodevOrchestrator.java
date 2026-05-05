package com.assurant.brain.jira;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.ClarificationSessionRepository;
import com.assurant.brain.dao.ProjectRepository;
import com.assurant.brain.domain.ClarificationSession;
import com.assurant.brain.domain.Project;
import com.assurant.brain.dto.request.AutodevCreatePrsRequest;
import com.assurant.brain.dto.response.AutodevPlanResponse;
import com.assurant.brain.dto.response.AutodevSessionResponse;
import com.assurant.brain.enums.SessionStatus;
import com.assurant.brain.facade.autodev.AutodevFacade;
import com.assurant.brain.facade.autodev.MultiRepoPrResult;
import com.assurant.brain.guardrail.RailChain;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.jira.dao.JiraIssueRunRepository;
import com.assurant.brain.jira.domain.JiraIssueRun;
import com.assurant.brain.jira.enums.JiraRunState;
import com.assurant.brain.jobs.AsyncJobService;
import com.assurant.brain.service.AffectedProject;
import com.assurant.brain.service.ProjectAffinityDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Log4j2
@Service
@RequiredArgsConstructor
public class JiraDrivenAutodevOrchestrator {

    public enum LabelTransition { START_FRESH, CLARIFY_AGAIN_WITH_COMMENTS, IMPLEMENT_PLAN, REDO_ANALYSIS, IGNORE }

    private static final double AFFINITY_MIN_CONFIDENCE = 0.6;

    private final JiraClient jiraClient;
    private final JiraIssueMapper jiraIssueMapper;
    private final JiraCommentFormatter commentFormatter;
    private final JiraIssueRunRepository runRepository;
    private final ClarificationSessionRepository sessionRepository;
    private final ProjectRepository projectRepository;
    private final ProjectAffinityDetector projectAffinityDetector;
    private final AutodevFacade autodevFacade;
    private final AsyncJobService asyncJobService;
    private final BrainProperties brainProperties;
    private final RailChain railChain;
    private final com.assurant.brain.sage.SageInquisitor sageInquisitor;

    @Autowired
    @Lazy
    private JiraDrivenAutodevOrchestrator self;

    @Async("brainLlmExecutor")
    public void handle(String issueKey, LabelTransition transition, UUID jobId) {
        try {
            asyncJobService.markRunning(jobId, "Reading Jira ticket " + issueKey);
            switch (transition) {
                case START_FRESH                 -> beginAnalysis(issueKey, jobId, true);
                case CLARIFY_AGAIN_WITH_COMMENTS -> beginAnalysis(issueKey, jobId, false);
                case REDO_ANALYSIS               -> beginAnalysis(issueKey, jobId, false);
                case IMPLEMENT_PLAN              -> implementAndCreatePrs(issueKey, jobId);
                case IGNORE                      -> asyncJobService.markSucceeded(jobId, Map.of("phase", "ignored"));
            }
        } catch (Exception e) {
            log.error("JiraDrivenAutodev failed for issue={}: {}", issueKey, e.getMessage(), e);
            asyncJobService.markFailed(jobId, e.getMessage());
            tryPostFailureComment(issueKey, e);
        }
    }

    public void beginAnalysis(String issueKey, UUID jobId, boolean fresh) {
        Map<String, Object> issue = jiraClient.getIssueWithComments(issueKey);
        String rawRequirement = buildRequirementText(issue);
        String requirement = railChain.applyPreLlm(
                RailContext.preLlm(null, "JiraDrivenAutodevOrchestrator", rawRequirement)).sanitized();

        SessionPrep prep = self.prepareSession(issueKey, requirement, fresh);
        sageInspect(prep, issueKey, requirement);

        if (prep.run().getAffinityProjectIds() == null || prep.run().getAffinityProjectIds().isEmpty()) {
            jiraClient.addCommentAdf(issueKey, commentFormatter.noAffinityFound(labelPrefix()));
            transitionToAnalysis(issueKey, prep.run());
            asyncJobService.markPartial(jobId, Map.of("phase", "no-affinity"));
            return;
        }

        AutodevSessionResponse sessionResp = fresh
                ? autodevFacade.start("jira", requirement, null, prep.session().getProjectId())
                : autodevFacade.clarify(prep.session().getId().toString(), requirement);

        if (!sessionResp.planReady()) {
            jiraClient.addCommentAdf(issueKey,
                    commentFormatter.questions(sessionResp.clarificationQuestions(), labelPrefix()));
            transitionToAnalysis(issueKey, prep.run());
            asyncJobService.markPartial(jobId, Map.of("phase", "questions-posted",
                    "questionCount", sessionResp.clarificationQuestions().size()));
            return;
        }

        AutodevPlanResponse planResp = autodevFacade.plan(sessionResp.sessionId());
        jiraClient.addCommentAdf(issueKey,
                commentFormatter.planSummary(requirement, prep.run().getAffinityProjectIds(), labelPrefix()));
        transitionToAnalysisDone(issueKey, prep.run());
        asyncJobService.markSucceeded(jobId, Map.of(
                "phase", "plan-posted",
                "sessionId", planResp.sessionId(),
                "affectedProjects", prep.run().getAffinityProjectIds()));
    }

    private void sageInspect(SessionPrep prep, String issueKey, String requirement) {
        try {
            String projectId = prep.run().getAffinityProjectIds() == null
                    || prep.run().getAffinityProjectIds().isEmpty()
                    ? null
                    : prep.run().getAffinityProjectIds().get(0);
            if (projectId == null) return;
            sageInquisitor.inspect(new com.assurant.brain.sage.BrainInputEvent(
                    com.assurant.brain.sage.BrainInputEventType.JIRA_WEBHOOK,
                    projectId, null, requirement,
                    java.util.List.of(), Map.of("issueKey", issueKey)));
        } catch (RuntimeException e) {
            log.debug("SAGE inspection skipped for issue={}: {}", issueKey, e.getMessage());
        }
    }

    public record SessionPrep(JiraIssueRun run, ClarificationSession session) {}

    @Transactional
    public SessionPrep prepareSession(String issueKey, String requirement, boolean fresh) {
        JiraIssueRun run = runRepository.findByIssueKey(issueKey).orElseGet(() -> {
            JiraIssueRun fresh1 = new JiraIssueRun();
            fresh1.setIssueKey(issueKey);
            fresh1.setState(JiraRunState.IDLE);
            return fresh1;
        });

        ClarificationSession session = (run.getSessionId() != null && !fresh)
                ? sessionRepository.findById(run.getSessionId()).orElse(null)
                : null;

        if (session == null) {
            session = new ClarificationSession();
            session.setProjectId("autodev");
            session.setRequirement(requirement);
            session.setRounds(new ArrayList<>());
            session.setStatus(SessionStatus.CLARIFYING);
            List<AffectedProject> affected = projectAffinityDetector.detect(requirement);
            session.setAffectedProjects(affectedToMaps(affected));
            session = sessionRepository.save(session);
            run.setAffinityProjectIds(affected.stream()
                    .filter(a -> a.confidence() >= AFFINITY_MIN_CONFIDENCE)
                    .map(AffectedProject::projectId)
                    .toList());
        } else {
            session.setRequirement(requirement);
            session = sessionRepository.save(session);
        }
        run.setSessionId(session.getId());
        run = runRepository.save(run);
        return new SessionPrep(run, session);
    }

    public void implementAndCreatePrs(String issueKey, UUID jobId) {
        JiraIssueRun run = runRepository.findByIssueKey(issueKey)
                .orElseThrow(() -> new IllegalStateException("No prior analysis for " + issueKey));
        if (run.getState() != JiraRunState.AI_DEV_ANALYSIS_DONE) {
            log.warn("Implement requested but issue={} state is {}", issueKey, run.getState());
        }
        if (run.getSessionId() == null) {
            throw new IllegalStateException("No session linked to issue " + issueKey);
        }

        autodevFacade.execute(run.getSessionId().toString(), run.getAffinityProjectIds());

        List<AutodevCreatePrsRequest.RepoSpec> repos = buildRepoSpecs(run.getAffinityProjectIds());
        if (repos.isEmpty()) {
            jiraClient.addCommentAdf(issueKey, commentFormatter.noAffinityFound(labelPrefix()));
            transitionToAnalysis(issueKey, run);
            asyncJobService.markPartial(jobId, Map.of("phase", "no-repos"));
            return;
        }

        MultiRepoPrResult batch = autodevFacade.createPrs(run.getSessionId().toString(), repos, issueKey);
        run.setPrBatchId(batch.batchId());

        jiraClient.addCommentAdf(issueKey, commentFormatter.prLinks(batch));
        transitionToReviewReady(issueKey, run);
        asyncJobService.markSucceeded(jobId, Map.of(
                "phase", "prs-created",
                "batchId", batch.batchId().toString(),
                "succeeded", batch.succeeded(),
                "failed", batch.failed()));
    }

    private List<AutodevCreatePrsRequest.RepoSpec> buildRepoSpecs(List<String> projectIds) {
        List<AutodevCreatePrsRequest.RepoSpec> specs = new ArrayList<>();
        if (projectIds == null) return specs;
        for (String id : projectIds) {
            Optional<Project> project = projectRepository.findById(id);
            if (project.isEmpty()) {
                log.warn("Project {} not found in registry; skipping", id);
                continue;
            }
            String repoUrl = project.get().getRepoUrl();
            String branch = project.get().getBranch() == null || project.get().getBranch().isBlank()
                    ? "master" : project.get().getBranch();
            if (repoUrl == null || repoUrl.isBlank()) {
                log.warn("Project {} has no repoUrl; skipping", id);
                continue;
            }
            specs.add(new AutodevCreatePrsRequest.RepoSpec(id, repoUrl, branch));
        }
        return specs;
    }

    private String buildRequirementText(Map<String, Object> issue) {
        String key = jiraIssueMapper.extractIssueKey(issue);
        StringBuilder sb = new StringBuilder();
        sb.append("Jira ticket: ").append(key).append('\n');
        sb.append("Title: ").append(jiraIssueMapper.extractSummary(issue)).append('\n');
        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) issue.get("fields");
        if (fields != null) {
            String description = extractText(fields.get("description"));
            if (!description.isBlank()) sb.append("\nDescription:\n").append(description).append('\n');
            @SuppressWarnings("unchecked")
            Map<String, Object> comment = (Map<String, Object>) fields.get("comment");
            if (comment != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> comments = (List<Map<String, Object>>) comment.get("comments");
                if (comments != null && !comments.isEmpty()) {
                    sb.append("\nComments:\n");
                    for (Map<String, Object> c : comments) {
                        String body = extractText(c.get("body"));
                        if (!body.isBlank()) sb.append("- ").append(body).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractText(Object adfNode) {
        if (adfNode == null) return "";
        if (adfNode instanceof String s) return s;
        if (!(adfNode instanceof Map<?, ?> map)) return "";
        Object text = ((Map<String, Object>) map).get("text");
        if (text instanceof String s) return s;
        Object content = ((Map<String, Object>) map).get("content");
        if (content instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object child : list) {
                String childText = extractText(child);
                if (!childText.isBlank()) sb.append(childText).append(' ');
            }
            return sb.toString().trim();
        }
        return "";
    }

    private List<Map<String, Object>> affectedToMaps(List<AffectedProject> projects) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AffectedProject p : projects) {
            out.add(Map.of(
                    "projectId", p.projectId(),
                    "confidence", p.confidence(),
                    "rationale", p.rationale() == null ? "" : p.rationale()));
        }
        return out;
    }

    private void transitionToAnalysis(String issueKey, JiraIssueRun run) {
        applyLabels(issueKey, prefix("ANALYSIS"),
                List.of(prefix("READY"), prefix("ANALYSIS_DONE"), prefix("REVIEW_READY")));
        run.setState(JiraRunState.AI_DEV_ANALYSIS);
        run.setLastLabel(prefix("ANALYSIS"));
        run.setLastTransitionAt(OffsetDateTime.now());
        runRepository.save(run);
    }

    private void transitionToAnalysisDone(String issueKey, JiraIssueRun run) {
        applyLabels(issueKey, prefix("ANALYSIS_DONE"),
                List.of(prefix("READY"), prefix("ANALYSIS"), prefix("REVIEW_READY")));
        run.setState(JiraRunState.AI_DEV_ANALYSIS_DONE);
        run.setLastLabel(prefix("ANALYSIS_DONE"));
        run.setLastTransitionAt(OffsetDateTime.now());
        runRepository.save(run);
    }

    private void transitionToReviewReady(String issueKey, JiraIssueRun run) {
        applyLabels(issueKey, prefix("REVIEW_READY"),
                List.of(prefix("READY"), prefix("ANALYSIS"), prefix("ANALYSIS_DONE")));
        run.setState(JiraRunState.AI_DEV_REVIEW_READY);
        run.setLastLabel(prefix("REVIEW_READY"));
        run.setLastTransitionAt(OffsetDateTime.now());
        runRepository.save(run);
    }

    private void applyLabels(String issueKey, String addLabel, List<String> removeLabels) {
        try {
            jiraClient.transitionLabels(issueKey, List.of(addLabel), removeLabels);
        } catch (RuntimeException e) {
            log.warn("Failed to transition labels on {}: {}", issueKey, e.getMessage());
        }
    }

    private void tryPostFailureComment(String issueKey, Throwable t) {
        try {
            jiraClient.addCommentAdf(issueKey, commentFormatter.failure(t));
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    private String prefix(String suffix) {
        return labelPrefix() + suffix;
    }

    private String labelPrefix() {
        BrainProperties.Jira jira = brainProperties.jira();
        String pref = jira == null ? null : jira.labelPrefix();
        return pref == null || pref.isBlank() ? "AI_DEV_" : pref;
    }
}
