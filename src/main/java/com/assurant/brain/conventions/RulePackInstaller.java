package com.assurant.brain.conventions;

import com.assurant.brain.graph.node.ConventionNode;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.jobs.AsyncJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Log4j2
@Service
@RequiredArgsConstructor
public class RulePackInstaller {

    private static final int MAX_CONVENTIONS_PER_PACK = 500;
    private static final int MAX_RULE_LENGTH = 2000;
    private static final double MIN_TRUST_WEIGHT = 0.1;
    private static final double MAX_TRUST_WEIGHT = 3.0;
    private static final double DEFAULT_TRUST_WEIGHT = 1.0;

    private final ConventionNodeRepository conventionNodeRepository;
    private final AsyncJobService asyncJobService;

    public record InstallResult(int installed, String sourceTag) {}
    public record UninstallResult(int removed, String sourceTag) {}

    @Async("brainLlmExecutor")
    public void installAsync(String projectId, RulePack pack, boolean thanosApproved, UUID jobId) {
        try {
            asyncJobService.markRunning(jobId,
                    "Installing rule-pack " + (pack == null ? "?" : pack.id()) + " on " + projectId);
            InstallResult result = install(projectId, pack, thanosApproved);
            asyncJobService.markSucceeded(jobId, Map.of(
                    "installed", result.installed(),
                    "sourceTag", result.sourceTag()));
        } catch (Exception e) {
            log.error("Rule-pack install job={} failed: {}", jobId, e.getMessage(), e);
            asyncJobService.markFailed(jobId,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    public InstallResult install(String projectId, RulePack pack, boolean thanosApproved) {
        validate(projectId, pack);
        if (!thanosApproved) {
            throw new IllegalStateException(
                    "RulePack install denied: THANOS approval required (set thanosApproved=true)");
        }

        String sourceTag = pack.sourceTag();
        List<ConventionNode> existing = conventionNodeRepository
                .findByProjectIdAndSourceFilePrefix(projectId, sourceTag);
        if (!existing.isEmpty()) {
            throw new IllegalStateException(
                    "RulePack '" + sourceTag + "' already installed for project " + projectId
                            + " (uninstall before re-installing)");
        }

        List<ConventionNode> toSave = new ArrayList<>();
        for (RulePack.PackConvention c : pack.conventions()) {
            if (c.rule() == null || c.rule().isBlank()) continue;
            String rule = c.rule().length() > MAX_RULE_LENGTH
                    ? c.rule().substring(0, MAX_RULE_LENGTH) : c.rule();
            ConventionNode node = new ConventionNode();
            node.setProjectId(projectId);
            node.setRule(rule);
            node.setCategory(c.category() == null ? "RULE_PACK" : c.category());
            node.setTrustWeight(clampTrustWeight(c.trustWeight()));
            node.setSourceFile(sourceTag);
            toSave.add(node);
        }
        conventionNodeRepository.saveAll(toSave);
        log.info("Installed rule-pack {} → project={} ({} conventions)", sourceTag, projectId, toSave.size());
        return new InstallResult(toSave.size(), sourceTag);
    }

    public UninstallResult uninstall(String projectId, String packId, String version) {
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId required");
        if (packId == null || packId.isBlank()) throw new IllegalArgumentException("packId required");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("version required");

        String sourceTag = "rulepack:" + packId + "@" + version;
        int beforeCount = conventionNodeRepository
                .findByProjectIdAndSourceFilePrefix(projectId, sourceTag).size();
        conventionNodeRepository.deleteByProjectIdAndSourceFilePrefix(projectId, sourceTag);
        log.info("Uninstalled rule-pack {} from project={} ({} conventions removed)",
                sourceTag, projectId, beforeCount);
        return new UninstallResult(beforeCount, sourceTag);
    }

    private void validate(String projectId, RulePack pack) {
        if (projectId == null || projectId.isBlank()) throw new IllegalArgumentException("projectId required");
        if (pack == null) throw new IllegalArgumentException("pack required");
        if (pack.id() == null || pack.id().isBlank()) throw new IllegalArgumentException("pack.id required");
        if (pack.version() == null || pack.version().isBlank()) throw new IllegalArgumentException("pack.version required");
        if (pack.conventions() == null || pack.conventions().isEmpty()) {
            throw new IllegalArgumentException("pack must contain at least one convention");
        }
        if (pack.conventions().size() > MAX_CONVENTIONS_PER_PACK) {
            throw new IllegalArgumentException(
                    "pack exceeds " + MAX_CONVENTIONS_PER_PACK + " conventions");
        }
    }

    private double clampTrustWeight(double raw) {
        if (raw <= 0) return DEFAULT_TRUST_WEIGHT;
        if (raw < MIN_TRUST_WEIGHT) return MIN_TRUST_WEIGHT;
        if (raw > MAX_TRUST_WEIGHT) return MAX_TRUST_WEIGHT;
        return raw;
    }
}
