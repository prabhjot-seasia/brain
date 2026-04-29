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
public class OutputLengthRail implements Rail {

    private static final int DEFAULT_MAX_OUTPUT_CHARS = 30_000;
    private static final int PRIORITY = 30;

    private final BrainProperties brainProperties;

    @Override
    public RailResult apply(RailContext context) {
        String output = context.rawOutput();
        if (output == null) return RailResult.pass(type());

        int max = brainProperties.guardrails() != null
                ? brainProperties.guardrails().maxOutputChars()
                : DEFAULT_MAX_OUTPUT_CHARS;

        if (output.length() > max) {
            return RailResult.block(type(),
                    "Output exceeds max length: " + output.length() + " > " + max);
        }
        return RailResult.pass(type());
    }

    @Override
    public RailPhase phase() { return RailPhase.POST_LLM; }

    @Override
    public int priority() { return PRIORITY; }

    @Override
    public RailType type() { return RailType.LENGTH; }
}
