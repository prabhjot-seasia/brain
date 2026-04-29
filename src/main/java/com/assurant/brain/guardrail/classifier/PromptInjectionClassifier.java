package com.assurant.brain.guardrail.classifier;

import java.util.List;

public interface PromptInjectionClassifier {

    ClassificationResult classify(String input);

    record ClassificationResult(boolean isInjection, double confidence, List<String> matchedPatterns) {

        public static ClassificationResult safe() {
            return new ClassificationResult(false, 0.0, List.of());
        }

        public static ClassificationResult injected(double confidence, List<String> patterns) {
            return new ClassificationResult(true, confidence, patterns);
        }
    }
}
