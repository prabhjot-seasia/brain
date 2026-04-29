package com.assurant.brain.guardrail.rails;

import com.assurant.brain.enums.RailPhase;
import com.assurant.brain.enums.RailType;
import com.assurant.brain.guardrail.Rail;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import com.assurant.brain.guardrail.classifier.PromptInjectionClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
public class PromptInjectionRail implements Rail {

    private static final int PRIORITY = 20;

    private final PromptInjectionClassifier classifier;

    @Override
    public RailResult apply(RailContext context) {
        String input = context.sanitizedInput();
        if (input == null || input.isBlank()) return RailResult.pass(type());

        PromptInjectionClassifier.ClassificationResult result = classifier.classify(input);
        if (result.isInjection()) {
            return RailResult.block(type(), result.matchedPatterns());
        }
        return RailResult.pass(type());
    }

    @Override
    public RailPhase phase() { return RailPhase.PRE_LLM; }

    @Override
    public int priority() { return PRIORITY; }

    @Override
    public RailType type() { return RailType.INJECTION; }
}
