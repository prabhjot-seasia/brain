package com.assurant.brain.sage;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SageAllowlist {

    private static final List<String> SYMBOL_PREFIXES = List.of(
            "java.", "javax.", "jakarta.",
            "org.springframework.", "org.springframework.boot.",
            "org.junit.", "org.assertj.", "org.mockito.",
            "lombok.", "org.slf4j.", "org.apache.logging.log4j.",
            "com.fasterxml.jackson.", "com.google.common.",
            "io.cucumber.", "org.openqa.selenium.",
            "org.hibernate.", "org.springframework.data.",
            "io.swagger.", "jakarta.persistence.", "jakarta.validation.");

    public boolean matches(SageInquisitor.DetectedGap gap) {
        if (gap == null || gap.type() != ContextGapType.SYMBOL_NOT_FOUND) return false;
        String id = gap.identifier();
        if (id == null) return false;
        for (String prefix : SYMBOL_PREFIXES) {
            if (id.startsWith(prefix)) return true;
        }
        return false;
    }
}
