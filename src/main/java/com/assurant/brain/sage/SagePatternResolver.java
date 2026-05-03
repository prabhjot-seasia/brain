package com.assurant.brain.sage;

import com.assurant.brain.graph.repository.ConventionNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Log4j2
@Component
@RequiredArgsConstructor
public class SagePatternResolver {

    private final ConventionNodeRepository conventionNodeRepository;

    public Optional<String> tryResolve(String projectId, SageInquisitor.DetectedGap gap) {
        if (gap == null || projectId == null || projectId.isBlank()) return Optional.empty();
        try {
            return switch (gap.type()) {
                case LOGGING_CONVENTION -> resolveLoggingConvention(projectId);
                case TEST_NAMING_CONVENTION -> resolveTestNamingConvention(projectId);
                default -> Optional.empty();
            };
        } catch (RuntimeException e) {
            log.debug("SagePatternResolver: lookup failed for project={} type={}: {}",
                    projectId, gap.type(), e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> resolveLoggingConvention(String projectId) {
        return conventionNodeRepository.findByProjectIdAndCategory(projectId, "logging").stream()
                .findFirst()
                .map(c -> c.getRule());
    }

    private Optional<String> resolveTestNamingConvention(String projectId) {
        return conventionNodeRepository.findByProjectIdAndCategory(projectId, "testing").stream()
                .findFirst()
                .map(c -> c.getRule());
    }
}
