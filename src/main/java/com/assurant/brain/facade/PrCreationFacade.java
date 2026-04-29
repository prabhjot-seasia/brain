package com.assurant.brain.facade;

import com.assurant.brain.codegen.CodeGeneratorService;
import com.assurant.brain.codegen.SelfReviewLoop;
import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.dao.ClarificationSessionRepository;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.ClarificationSession;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.enums.PrStatus;
import com.assurant.brain.enums.SessionStatus;
import com.assurant.brain.exceptions.SessionNotFoundException;
import com.assurant.brain.github.PrCreationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Log4j2
@Component
@RequiredArgsConstructor
public class PrCreationFacade {

    private final CodeGeneratorService codeGeneratorService;
    private final SelfReviewLoop selfReviewLoop;
    private final PrCreationService prCreationService;
    private final ClarificationSessionRepository sessionRepository;
    private final PullRequestRecordRepository prRecordRepository;
    private final BrainProperties brainProperties;
    private final ObjectMapper objectMapper;

    @Transactional
    public PullRequestRecord createPr(UUID sessionId, String repoUrl, String baseBranch) {
        ClarificationSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new SessionNotFoundException("Session not found: " + sessionId));

        if (session.getStatus() != SessionStatus.COMPLETE) {
            throw new IllegalStateException(
                    "Cannot create PR from session in status " + session.getStatus() +
                    ". Session must be COMPLETE (100% clarified) before code generation.");
        }

        if (session.getFinalPlan() == null) {
            throw new IllegalStateException(
                    "Session has no final plan. Run analysis until plan is generated before creating a PR.");
        }

        PullRequestRecord record = new PullRequestRecord();
        record.setSessionId(sessionId);
        record.setRepoUrl(repoUrl);
        record.setBaseBranch(baseBranch);
        record.setStatus(PrStatus.GENERATING);
        record = prRecordRepository.save(record);

        try {
            String planJson = objectMapper.writeValueAsString(session.getFinalPlan());

            Map<String, String> generatedFiles = codeGeneratorService.generateCode(
                    session.getProjectId(), planJson, session.getRequirement());

            record.setGeneratedFiles(generatedFiles);
            record.setStatus(PrStatus.REVIEWING);
            prRecordRepository.save(record);

            int maxIterations = brainProperties.github().maxSelfReviewIterations();
            generatedFiles = selfReviewLoop.run(record, generatedFiles, planJson, maxIterations);

            record.setGeneratedFiles(generatedFiles);
            record.setStatus(PrStatus.CREATING);
            prRecordRepository.save(record);

            String planSummary = extractPlanSummary(session);
            PrCreationService.PrResult prResult = prCreationService.createPullRequest(
                    repoUrl, baseBranch, generatedFiles, planSummary);

            record.setBranchName(prResult.branchName());
            record.setPrNumber(prResult.prNumber());
            record.setPrUrl(prResult.prUrl());
            record.setStatus(PrStatus.CREATED);
            return prRecordRepository.save(record);

        } catch (Exception e) {
            log.error("PR creation failed for session={}: {}", sessionId, e.getMessage(), e);
            record.setStatus(PrStatus.FAILED);
            record.setErrorMessage(e.getMessage());
            prRecordRepository.save(record);
            throw new IllegalStateException("PR creation failed: " + e.getMessage());
        }
    }

    private String extractPlanSummary(ClarificationSession session) {
        Map<String, Object> plan = session.getFinalPlan();
        if (plan.containsKey("requirement")) {
            return plan.get("requirement").toString();
        }
        return session.getRequirement();
    }
}
