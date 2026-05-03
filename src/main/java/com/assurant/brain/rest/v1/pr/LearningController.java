package com.assurant.brain.rest.v1.pr;

import com.assurant.brain.dao.CiRemediationAttemptRepository;
import com.assurant.brain.dao.LearningEventRepository;
import com.assurant.brain.domain.CiRemediationAttempt;
import com.assurant.brain.domain.LearningEvent;
import com.assurant.brain.learning.MergedPrAnalyzerService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class LearningController {

    private final MergedPrAnalyzerService mergedPrAnalyzerService;
    private final CiRemediationAttemptRepository remediationRepository;
    private final LearningEventRepository learningEventRepository;
    private final com.assurant.brain.dao.GeneratedDocumentRepository generatedDocumentRepository;
    private final com.assurant.brain.confluence.ConfluencePublisherService confluencePublisherService;
    private final com.assurant.brain.config.properties.BrainProperties brainProperties;

    @PostMapping("/pr/{id}/analyze-merge")
    public ResponseEntity<List<LearningEvent>> analyzeMergedPr(
            @PathVariable UUID id,
            @RequestParam @NotBlank String projectId) {
        List<LearningEvent> events = mergedPrAnalyzerService.analyzeAndLearn(id, projectId);
        maybeRepublishConfluenceDoc(projectId);
        return ResponseEntity.ok(events);
    }

    private void maybeRepublishConfluenceDoc(String projectId) {
        var conf = brainProperties.confluence();
        if (conf == null || !conf.enabled() || !conf.publishOnPrMerge()) return;
        generatedDocumentRepository.findFirstByProjectIdAndConfluencePageIdIsNotNullOrderByConfluencePublishedAtDesc(projectId)
                .ifPresent(confluencePublisherService::updateExisting);
    }

    @GetMapping("/pr/{id}/remediations")
    public ResponseEntity<List<CiRemediationAttempt>> getRemediations(@PathVariable UUID id) {
        return ResponseEntity.ok(
                remediationRepository.findByPrRecordIdOrderByAttemptNumberAsc(id));
    }

    @GetMapping("/learning/events")
    public ResponseEntity<List<LearningEvent>> getLearningEvents(
            @RequestParam(required = false) String projectId) {
        if (projectId != null) {
            return ResponseEntity.ok(learningEventRepository.findByProjectIdOrderByCreatedAtDesc(projectId));
        }
        return ResponseEntity.ok(learningEventRepository.findAllByOrderByCreatedAtDesc());
    }

    @GetMapping("/learning/events/pr/{prRecordId}")
    public ResponseEntity<List<LearningEvent>> getLearningEventsByPr(@PathVariable UUID prRecordId) {
        return ResponseEntity.ok(learningEventRepository.findByPrRecordIdOrderByCreatedAtDesc(prRecordId));
    }
}
