package com.assurant.brain.learning;

import com.assurant.brain.config.properties.BrainProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ConventionKeyExtractor {

    private final BrainProperties brainProperties;

    private int maxFallbackLength() { return brainProperties.learning() != null ? brainProperties.learning().conventionKeyMaxLength() : 30; }

    public String extract(String conventionRule) {
        if (conventionRule == null) return "";
        String lower = conventionRule.toLowerCase();
        if (lower.contains("constructor") || lower.contains("requiredargsconstructor")) return "constructor-injection";
        if (lower.contains("log4j2") || lower.contains("slf4j")) return "log4j2-usage";
        if (lower.contains("autowired") || lower.contains("field injection")) return "no-field-injection";
        if (lower.contains("enum") && lower.contains("status")) return "enum-status";
        if (lower.contains("comment") || lower.contains("javadoc")) return "no-comments";
        if (lower.contains("injection") || lower.contains("prompt")) return "prompt-injection";
        if (lower.contains("pii") || lower.contains("ssn") || lower.contains("credit card")) return "pii-leak";
        if (lower.contains("schema") || lower.contains("json")) return "schema-violation";
        int maxLen = maxFallbackLength();
        return lower.length() > maxLen ? lower.substring(0, maxLen) : lower;
    }
}
