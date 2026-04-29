package com.assurant.brain.guardrail.classifier;

import jakarta.annotation.PostConstruct;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Log4j2
@Component
public class RegexInjectionClassifier implements PromptInjectionClassifier {

    private static final String PATTERNS_RESOURCE = "guardrails/prompt-injection-patterns.txt";

    private List<Pattern> patterns;

    @PostConstruct
    void loadPatterns() {
        patterns = new ArrayList<>();
        ClassPathResource resource = new ClassPathResource(PATTERNS_RESOURCE);
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    patterns.add(Pattern.compile(trimmed));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load prompt injection patterns from " + PATTERNS_RESOURCE, e);
        }
        log.info("Loaded {} prompt injection patterns", patterns.size());
    }

    @Override
    public ClassificationResult classify(String input) {
        if (input == null || input.isBlank()) {
            return ClassificationResult.safe();
        }

        List<String> matches = new ArrayList<>();
        for (Pattern pattern : patterns) {
            if (pattern.matcher(input).find()) {
                matches.add(pattern.pattern());
            }
        }

        if (matches.isEmpty()) {
            return ClassificationResult.safe();
        }
        return ClassificationResult.injected(Math.min(1.0, 0.5 + 0.1 * matches.size()), matches);
    }
}
