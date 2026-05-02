package com.assurant.brain.codegen;

import com.assurant.brain.dao.LearningEventRepository;
import com.assurant.brain.dao.ProjectRepository;
import com.assurant.brain.domain.LearningEvent;
import com.assurant.brain.domain.Project;
import com.assurant.brain.enums.LearningEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Log4j2
@Component
@RequiredArgsConstructor
public class StyleFingerprintAuditTask {

    private static final int CODE_SAMPLE_SIZE = 30;
    private static final double DRIFT_SIGMA_THRESHOLD = 1.5;

    private final ProjectRepository projectRepository;
    private final VectorStore vectorStore;
    private final StyleFingerprintBuilder styleFingerprintBuilder;
    private final LearningEventRepository learningEventRepository;

    @Value("${brain.style.audit-cron:0 15 4 * * *}")
    private String cron;

    @Value("${brain.style.audit-enabled:true}")
    private boolean enabled;

    @Scheduled(cron = "${brain.style.audit-cron:0 15 4 * * *}")
    @Transactional
    public void auditAllProjects() {
        if (!enabled) {
            log.debug("StyleFingerprintAuditTask: disabled via brain.style.audit-enabled=false");
            return;
        }
        List<Project> projects;
        try {
            projects = projectRepository.findAll();
        } catch (RuntimeException e) {
            log.warn("StyleFingerprintAuditTask: project listing failed: {}", e.getMessage());
            return;
        }
        if (projects.isEmpty()) return;
        log.info("StyleFingerprintAuditTask: auditing {} project(s)", projects.size());
        for (Project project : projects) {
            try {
                auditOne(project.getId());
            } catch (RuntimeException e) {
                log.warn("StyleFingerprintAuditTask: skipped project={}: {}", project.getId(), e.getMessage());
            }
        }
    }

    void auditOne(String projectId) {
        if (projectId == null || projectId.isBlank()) return;
        StyleFingerprint current = sampleAndFingerprint(projectId);
        if (current.isEmpty()) {
            log.debug("StyleFingerprintAuditTask: project={} has no code samples yet, skipping", projectId);
            return;
        }
        Optional<LearningEvent> previous = learningEventRepository
                .findFirstByProjectIdAndEventTypeOrderByCreatedAtDesc(
                        projectId, LearningEventType.STYLE_FINGERPRINT_SNAPSHOT);

        Map<String, Object> currentSnapshot = toMap(current);

        if (previous.isPresent()) {
            StyleFingerprint prev = fromMap(previous.get().getDetails());
            double deltaSigma = sigmaOff(current.avgMethodLines(), prev);
            if (deltaSigma >= DRIFT_SIGMA_THRESHOLD) {
                LearningEvent driftEvent = new LearningEvent();
                driftEvent.setProjectId(projectId);
                driftEvent.setEventType(LearningEventType.STYLE_FINGERPRINT_DRIFT);
                driftEvent.setConventionRule(String.format(
                        "avg method length drifted from %.1f to %.1f (%.1fσ)",
                        prev.avgMethodLines(), current.avgMethodLines(), deltaSigma));
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("previous", toMap(prev));
                details.put("current", currentSnapshot);
                details.put("deltaSigma", deltaSigma);
                driftEvent.setDetails(details);
                learningEventRepository.save(driftEvent);
                log.info("StyleFingerprintAuditTask: drift detected for project={} ({}σ)",
                        projectId, String.format("%.1f", deltaSigma));
            }
        }

        LearningEvent snapshot = new LearningEvent();
        snapshot.setProjectId(projectId);
        snapshot.setEventType(LearningEventType.STYLE_FINGERPRINT_SNAPSHOT);
        snapshot.setConventionRule(String.format("style fingerprint snapshot — avg method %.1f lines, %d sampled",
                current.avgMethodLines(), current.sampledMethods()));
        snapshot.setDetails(currentSnapshot);
        learningEventRepository.save(snapshot);
    }

    private StyleFingerprint sampleAndFingerprint(String projectId) {
        var b = new FilterExpressionBuilder();
        try {
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query("class implementation")
                    .topK(CODE_SAMPLE_SIZE)
                    .filterExpression(b.and(
                            b.eq("projectId", projectId),
                            b.eq("sourceType", "CODE")).build())
                    .build());
            if (docs == null || docs.isEmpty()) return StyleFingerprint.EMPTY;
            List<String> contents = new ArrayList<>(docs.size());
            for (Document d : docs) {
                if (d.getText() != null && !d.getText().isBlank()) contents.add(d.getText());
            }
            return styleFingerprintBuilder.build(contents);
        } catch (RuntimeException e) {
            log.debug("StyleFingerprintAuditTask: sampling failed for project={}: {}", projectId, e.getMessage());
            return StyleFingerprint.EMPTY;
        }
    }

    private Map<String, Object> toMap(StyleFingerprint fp) {
        Map<String, Object> m = new HashMap<>();
        m.put("sampledMethods", fp.sampledMethods());
        m.put("avgMethodLines", fp.avgMethodLines());
        m.put("methodLinesStdev", fp.methodLinesStdev());
        m.put("p90MethodLines", fp.p90MethodLines());
        m.put("returnEarlyRatio", fp.returnEarlyRatio());
        m.put("varUsageRatio", fp.varUsageRatio());
        m.put("streamUsageRatio", fp.streamUsageRatio());
        m.put("lambdaUsageRatio", fp.lambdaUsageRatio());
        m.put("avgImportsPerFile", fp.avgImportsPerFile());
        m.put("commentDensityPct", fp.commentDensityPct());
        return m;
    }

    private StyleFingerprint fromMap(Map<String, Object> m) {
        if (m == null || m.isEmpty()) return StyleFingerprint.EMPTY;
        return new StyleFingerprint(
                intOf(m, "sampledMethods"),
                doubleOf(m, "avgMethodLines"),
                doubleOf(m, "methodLinesStdev"),
                intOf(m, "p90MethodLines"),
                doubleOf(m, "returnEarlyRatio"),
                doubleOf(m, "varUsageRatio"),
                doubleOf(m, "streamUsageRatio"),
                doubleOf(m, "lambdaUsageRatio"),
                intOf(m, "avgImportsPerFile"),
                doubleOf(m, "commentDensityPct"));
    }

    private int intOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.intValue();
        return 0;
    }

    private double doubleOf(Map<String, Object> m, String key) {
        Object v = m.get(key);
        if (v instanceof Number n) return n.doubleValue();
        return 0d;
    }

    private double sigmaOff(double candidate, StyleFingerprint baseline) {
        if (baseline.methodLinesStdev() <= 0) {
            double absolute = Math.abs(candidate - baseline.avgMethodLines());
            return baseline.avgMethodLines() > 0 ? absolute / Math.max(1.0, baseline.avgMethodLines() * 0.1) : 0;
        }
        return Math.abs(candidate - baseline.avgMethodLines()) / baseline.methodLinesStdev();
    }
}
