package com.assurant.brain.ingest;

import com.assurant.brain.graph.node.ProjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Log4j2
@Service
@RequiredArgsConstructor
public class ContextGapDetector {

    public static final int MAX_GAP_QUESTIONS_PER_SESSION = 3;

    private static final Pattern CONFIG_TABLE_REFERENCE = Pattern.compile(
            "(?i)\\b(carrier_config|feature_flags?|app_config|settings|tenant_config)\\b");
    private static final Pattern SECRET_REFERENCE = Pattern.compile(
            "(?i)\\b([A-Z][A-Z0-9_]*_(?:KEY|SECRET|TOKEN|PASSWORD))\\b");
    private static final Pattern ENV_PLACEHOLDER = Pattern.compile(
            "\\$\\{([a-zA-Z][a-zA-Z0-9._-]*)}");

    public List<ContextGap> detect(ProjectNode projectNode, String planText) {
        if (projectNode == null || planText == null || planText.isBlank()) return List.of();

        List<ContextGap> gaps = new ArrayList<>();
        addConfigTableGaps(projectNode, planText, gaps);
        addSecretGaps(planText, gaps);
        addUnresolvedPlaceholderGaps(planText, gaps);

        return capToMaximum(gaps);
    }

    private void addConfigTableGaps(ProjectNode projectNode, String planText, List<ContextGap> gaps) {
        var matcher = CONFIG_TABLE_REFERENCE.matcher(planText);
        while (matcher.find()) {
            String tableName = matcher.group(1);
            boolean tableKnown = projectNode.getOwnedTables().stream()
                    .anyMatch(t -> tableName.equalsIgnoreCase(t.getTableName()));
            if (!tableKnown) {
                gaps.add(new ContextGap(
                        ContextGapType.CONFIG_TABLE_DATA,
                        "Plan references config table '" + tableName
                                + "'. No DDL or row snapshot ingested. Upload a CSV or schema dump?",
                        Map.of("tableName", tableName)));
            }
        }
    }

    private void addSecretGaps(String planText, List<ContextGap> gaps) {
        var matcher = SECRET_REFERENCE.matcher(planText);
        while (matcher.find()) {
            String secretName = matcher.group(1);
            gaps.add(new ContextGap(
                    ContextGapType.SECRET_VALUE_NEEDED,
                    "Plan exercises code path that reads secret '" + secretName
                            + "'. Provide a redacted value (session-only, never persisted)?",
                    Map.of("secretName", secretName)));
        }
    }

    private void addUnresolvedPlaceholderGaps(String planText, List<ContextGap> gaps) {
        var matcher = ENV_PLACEHOLDER.matcher(planText);
        while (matcher.find()) {
            String key = matcher.group(1);
            if (key.startsWith("BRAIN_")) continue;
            gaps.add(new ContextGap(
                    ContextGapType.ENV_SPECIFIC_VALUE,
                    "Plan references placeholder '${" + key + "}' that is not resolved. Provide value for target environment?",
                    Map.of("placeholder", key)));
        }
    }

    private List<ContextGap> capToMaximum(List<ContextGap> gaps) {
        if (gaps.size() <= MAX_GAP_QUESTIONS_PER_SESSION) return gaps;
        log.info("ContextGapDetector capping {} gaps to {}", gaps.size(), MAX_GAP_QUESTIONS_PER_SESSION);
        return gaps.subList(0, MAX_GAP_QUESTIONS_PER_SESSION);
    }
}
