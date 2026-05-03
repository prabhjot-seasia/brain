package com.assurant.brain.testing;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class TestScenarioCsvWriter {

    private static final String HEADER = "Scenario,Type,Status\n";

    public byte[] renderHumanCsv(List<TestScenario> scenarios) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER);
        if (scenarios != null) {
            for (TestScenario s : scenarios) {
                sb.append(quote(s.description())).append(',');
                sb.append(humanType(s.type())).append(',');
                sb.append(humanStatus(s.status())).append('\n');
            }
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    public String renderMarkdownTable(List<TestScenario> scenarios) {
        StringBuilder sb = new StringBuilder();
        sb.append("| # | Scenario | Type | Status |\n");
        sb.append("|---|----------|------|--------|\n");
        if (scenarios != null) {
            int i = 1;
            for (TestScenario s : scenarios) {
                sb.append("| ").append(i++).append(" | ")
                        .append(escapeMd(s.description())).append(" | ")
                        .append(humanType(s.type())).append(" | ")
                        .append(humanStatus(s.status())).append(" |\n");
            }
        }
        return sb.toString();
    }

    private String quote(String value) {
        if (value == null) return "";
        boolean needsQuoting = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        if (!needsQuoting) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private String escapeMd(String value) {
        if (value == null) return "";
        return value.replace("|", "\\|").replace("\n", " ");
    }

    private String humanType(TestScenarioType type) {
        return type == TestScenarioType.AUTOMATED ? "Automated" : "Manual";
    }

    private String humanStatus(TestScenarioStatus status) {
        return switch (status) {
            case COVERED -> "Covered";
            case MANUAL_REQUIRED -> "Needs QA";
            case QA_ADDED -> "QA-added";
            case PENDING -> "Pending";
        };
    }
}
