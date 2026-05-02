package com.assurant.brain.codegen;

import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Log4j2
@Component
public class EditBoundaryEnforcer {

    public record EnforcementResult(boolean ok, List<String> violations) {
        public static EnforcementResult clean() {
            return new EnforcementResult(true, List.of());
        }
    }

    public EnforcementResult enforce(EditBoundary boundary,
                                       Map<String, String> previousFiles,
                                       Map<String, String> generatedFiles) {
        if (boundary == null || boundary.isUnbounded()) return EnforcementResult.clean();
        if (generatedFiles == null || generatedFiles.isEmpty()) return EnforcementResult.clean();

        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, String> entry : generatedFiles.entrySet()) {
            String path = entry.getKey();
            if (!boundary.fileAllowed(path)) {
                violations.add("boundary: file '" + path
                        + "' was modified but is not in mayTouchFiles=" + boundary.mayTouchFiles());
                continue;
            }
            String previous = previousFiles == null ? "" : previousFiles.getOrDefault(path, "");
            int ratioPct = changedRatioPct(previous, entry.getValue());
            int cap = boundary.effectiveMaxEditRatioPct();
            if (ratioPct > cap) {
                violations.add("boundary: file '" + path
                        + "' edit-ratio " + ratioPct + "% exceeds cap " + cap
                        + "% — scope creep suspected");
            }
        }

        if (boundary.mustNotTouchSymbols() != null) {
            for (String forbidden : boundary.mustNotTouchSymbols()) {
                for (Map.Entry<String, String> entry : generatedFiles.entrySet()) {
                    String content = entry.getValue();
                    if (content != null && content.contains(forbidden)) {
                        violations.add("boundary: forbidden symbol '" + forbidden
                                + "' appears in '" + entry.getKey() + "' (denylisted)");
                    }
                }
            }
        }

        return new EnforcementResult(violations.isEmpty(), violations);
    }

    private int changedRatioPct(String previous, String generated) {
        if (generated == null || generated.isBlank()) return 0;
        if (previous == null || previous.isBlank()) return 0;
        String[] prevLines = previous.split("\n", -1);
        String[] genLines = generated.split("\n", -1);
        java.util.Set<String> prevSet = new java.util.HashSet<>(java.util.Arrays.asList(prevLines));
        int changed = 0;
        for (String line : genLines) {
            if (!prevSet.contains(line)) changed++;
        }
        int total = Math.max(prevLines.length, genLines.length);
        if (total == 0) return 0;
        return (int) Math.round(100.0 * changed / total);
    }
}
