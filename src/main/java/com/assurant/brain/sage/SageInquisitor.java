package com.assurant.brain.sage;

import com.assurant.brain.sage.dao.ContextGapResolutionRepository;
import com.assurant.brain.sage.domain.ContextGapResolution;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

@Log4j2
@Service
@RequiredArgsConstructor
public class SageInquisitor {

    static final int MAX_TIER1_PER_POST = 10;
    static final int MAX_TIER2_PER_CYCLE = 2;
    static final int MAX_TIER3_PER_CYCLE = 1;

    private final ContextGapResolutionRepository repository;
    private final SagePatternResolver patternResolver;
    private final SageCrossProjectMemory crossProjectMemory;
    private final SageAllowlist allowlist;

    @Transactional
    public SageInspectionResult inspect(BrainInputEvent event) {
        if (event == null || event.projectId() == null || event.projectId().isBlank()) {
            return SageInspectionResult.empty();
        }
        List<DetectedGap> detected = detectFromInput(event);
        return runReductionPasses(event.projectId(), detected, event.metadata() == null
                ? null : (String) event.metadata().get("issueKey"));
    }

    @Transactional
    public SageInspectionResult audit(BrainOutputEvent event) {
        if (event == null || event.projectId() == null || event.projectId().isBlank()) {
            return SageInspectionResult.empty();
        }
        List<DetectedGap> detected = detectFromOutput(event);
        return runReductionPasses(event.projectId(), detected, null);
    }

    public List<ContextGapResolution> openGaps(String projectId, int limit) {
        return repository.findByProjectIdAndStatusInOrderByTierAscCreatedAtAsc(
                projectId, List.of(GapStatus.PENDING, GapStatus.DEFERRED),
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)));
    }

    public Optional<ContextGapResolution> resolveAnswer(String projectId, String gapSignature,
                                                         String answerKind, String answerValue) {
        Optional<ContextGapResolution> gap = repository.findByProjectIdAndGapSignature(projectId, gapSignature);
        gap.ifPresent(g -> {
            g.setAnswerKind(answerKind);
            g.setAnswerValue(answerValue);
            g.setAnswerSource(AnswerSource.HUMAN);
            g.setStatus(GapStatus.RESOLVED);
            g.setConfidence(BigDecimal.ONE);
            repository.save(g);
        });
        return gap;
    }

    public ContextReadinessReport readinessFor(String projectId) {
        List<ContextGapResolution> open = repository
                .findByProjectIdAndStatusInOrderByTierAscCreatedAtAsc(
                        projectId, List.of(GapStatus.PENDING, GapStatus.DEFERRED),
                        org.springframework.data.domain.PageRequest.of(0, 100));
        int t1 = (int) open.stream().filter(g -> g.getTier() == 1).count();
        int t2 = (int) open.stream().filter(g -> g.getTier() == 2).count();
        int t3 = (int) open.stream().filter(g -> g.getTier() == 3).count();
        long resolved = repository.findByProjectIdAndStatusOrderByCreatedAtAsc(projectId, GapStatus.RESOLVED).size();
        long autoResolved = repository.findByProjectIdAndStatusOrderByCreatedAtAsc(projectId, GapStatus.AUTO_RESOLVED).size();
        String summary = t1 == 0
                ? "All Tier 1 gaps resolved. " + t2 + " conventions worth confirming when convenient."
                : t1 + " Tier 1 gaps unresolved — code generation must NOT proceed without acknowledgement.";
        return new ContextReadinessReport(t1, t2, t3, (int) (resolved + autoResolved),
                open.stream().filter(g -> g.getTier() == 1).toList(), summary);
    }

    private List<DetectedGap> detectFromInput(BrainInputEvent event) {
        List<DetectedGap> gaps = new ArrayList<>();
        if (event.extractedSymbols() != null) {
            for (String symbol : event.extractedSymbols()) {
                gaps.add(new DetectedGap(
                        ContextGapType.SYMBOL_NOT_FOUND,
                        "Symbol `" + symbol + "` referenced but not yet resolved",
                        symbol,
                        ContextGapType.SYMBOL_NOT_FOUND.defaultTier()));
            }
        }
        return gaps;
    }

    private List<DetectedGap> detectFromOutput(BrainOutputEvent event) {
        List<DetectedGap> gaps = new ArrayList<>();
        if (event.referencedSymbols() != null) {
            for (String symbol : event.referencedSymbols()) {
                gaps.add(new DetectedGap(
                        ContextGapType.SYMBOL_NOT_FOUND,
                        "Symbol `" + symbol + "` introduced by generated code but not in dictionary",
                        symbol,
                        ContextGapType.SYMBOL_NOT_FOUND.defaultTier()));
            }
        }
        return gaps;
    }

    private SageInspectionResult runReductionPasses(String projectId, List<DetectedGap> detected, String linkedIssueKey) {
        List<ContextGapResolution> created = new ArrayList<>();
        List<ContextGapResolution> autoResolved = new ArrayList<>();

        for (DetectedGap gap : detected) {
            String signature = signatureFor(gap);
            Optional<ContextGapResolution> existing = repository.findByProjectIdAndGapSignature(projectId, signature);
            if (existing.isPresent()) continue;

            if (allowlist.matches(gap)) {
                ContextGapResolution resolved = persistAutoResolved(projectId, signature, gap, linkedIssueKey,
                        AnswerSource.ALLOWLIST, "ALLOWLISTED", "stdlib or known third-party");
                autoResolved.add(resolved);
                continue;
            }

            Optional<String> patternMatch = patternResolver.tryResolve(projectId, gap);
            if (patternMatch.isPresent()) {
                ContextGapResolution resolved = persistAutoResolved(projectId, signature, gap, linkedIssueKey,
                        AnswerSource.PATTERN_RECOGNITION, "INFERRED", patternMatch.get());
                autoResolved.add(resolved);
                continue;
            }

            Optional<String> crossMatch = crossProjectMemory.tryResolve(gap);
            if (crossMatch.isPresent()) {
                ContextGapResolution resolved = persistAutoResolved(projectId, signature, gap, linkedIssueKey,
                        AnswerSource.CROSS_PROJECT_ANSWER, "REUSED", crossMatch.get());
                autoResolved.add(resolved);
                continue;
            }

            ContextGapResolution pending = persistPending(projectId, signature, gap, linkedIssueKey);
            created.add(pending);
        }

        ContextReadinessReport report = readinessFor(projectId);
        return new SageInspectionResult(created, autoResolved,
                report.tier1Unresolved(), report.tier2Unresolved(), report.tier3Unresolved(),
                List.of());
    }

    private ContextGapResolution persistPending(String projectId, String signature,
                                                  DetectedGap gap, String linkedIssueKey) {
        ContextGapResolution r = new ContextGapResolution();
        r.setProjectId(projectId);
        r.setGapSignature(signature);
        r.setGapType(gap.type());
        r.setTier((short) gap.tier());
        r.setStatus(GapStatus.PENDING);
        r.setQuestion(gap.question());
        r.setLinkedIssueKey(linkedIssueKey);
        return repository.save(r);
    }

    private ContextGapResolution persistAutoResolved(String projectId, String signature, DetectedGap gap,
                                                      String linkedIssueKey, AnswerSource source,
                                                      String answerKind, String answerValue) {
        ContextGapResolution r = new ContextGapResolution();
        r.setProjectId(projectId);
        r.setGapSignature(signature);
        r.setGapType(gap.type());
        r.setTier((short) gap.tier());
        r.setStatus(GapStatus.AUTO_RESOLVED);
        r.setQuestion(gap.question());
        r.setAnswerKind(answerKind);
        r.setAnswerValue(answerValue);
        r.setAnswerSource(source);
        r.setConfidence(BigDecimal.valueOf(0.95));
        r.setLinkedIssueKey(linkedIssueKey);
        return repository.save(r);
    }

    private String signatureFor(DetectedGap gap) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String payload = gap.type().name() + "|" + (gap.identifier() == null ? "" : gap.identifier());
            return HexFormat.of().formatHex(md.digest(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    .substring(0, 64);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record DetectedGap(ContextGapType type, String question, String identifier, int tier) {}

    public record ContextReadinessReport(int tier1Unresolved, int tier2Unresolved, int tier3Unresolved,
                                          int totalResolved, List<ContextGapResolution> blockingGaps,
                                          String summary) {}
}
