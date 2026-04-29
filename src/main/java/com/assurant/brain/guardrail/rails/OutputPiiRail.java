package com.assurant.brain.guardrail.rails;

import com.assurant.brain.enums.RailPhase;
import com.assurant.brain.enums.RailType;
import com.assurant.brain.guardrail.Rail;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@Component
@RequiredArgsConstructor
public class OutputPiiRail implements Rail {

    private static final int PRIORITY = 20;

    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CREDIT_CARD = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");

    @Override
    public RailResult apply(RailContext context) {
        String output = context.rawOutput();
        if (output == null || output.isBlank()) return RailResult.pass(type());

        List<String> leaked = new ArrayList<>();
        findMatches(output, SSN, "[PII-SSN]", leaked);
        findMatches(output, CREDIT_CARD, "[PII-CREDIT-CARD]", leaked);
        findMatches(output, JWT, "[PII-JWT]", leaked);

        if (leaked.isEmpty()) return RailResult.pass(type());

        log.warn("LLM output leaked PII: {} entities in {}", leaked.size(), context.sourceService());
        return RailResult.block(type(), List.of("LLM response contains PII: " + leaked.size() + " entities"));
    }

    private void findMatches(String text, Pattern pattern, String label, List<String> found) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            found.add(label);
        }
    }

    @Override
    public RailPhase phase() { return RailPhase.POST_LLM; }

    @Override
    public int priority() { return PRIORITY; }

    @Override
    public RailType type() { return RailType.PII; }
}
