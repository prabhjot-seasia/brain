package com.assurant.brain.guardrail.rails;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.RailPhase;
import com.assurant.brain.enums.RailType;
import com.assurant.brain.guardrail.Rail;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

@Log4j2
@Component
@RequiredArgsConstructor
public class InputLengthRail implements Rail {

    private static final int DEFAULT_MAX_INPUT_CHARS = 20_000;
    private static final int PRIORITY = 10;

    private final BrainProperties brainProperties;

    @Override
    public RailResult apply(RailContext context) {
        String input = context.sanitizedInput();
        if (input == null) return RailResult.pass(type());

        int max = brainProperties.guardrails() != null
                ? brainProperties.guardrails().maxInputChars()
                : DEFAULT_MAX_INPUT_CHARS;

        if (input.length() > max) {
            return RailResult.block(type(),
                    "Input exceeds max length: " + input.length() + " > " + max);
        }
        return RailResult.pass(type());
    }

    @Override
    public RailPhase phase() { return RailPhase.PRE_LLM; }

    @Override
    public int priority() { return PRIORITY; }

    @Override
    public RailType type() { return RailType.LENGTH; }
}
