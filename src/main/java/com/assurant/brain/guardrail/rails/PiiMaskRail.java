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

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@Component
@RequiredArgsConstructor
public class PiiMaskRail implements Rail {

    private static final int PRIORITY = 30;

    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern EMAIL = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern PHONE = Pattern.compile("\\b(?:\\+?1[-.\\s]?)?\\(?[2-9]\\d{2}\\)?[-.\\s]?\\d{3}[-.\\s]?\\d{4}\\b");
    private static final Pattern CREDIT_CARD = Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b");
    private static final Pattern IBAN = Pattern.compile("\\b[A-Z]{2}\\d{2}[A-Z0-9]{4,30}\\b");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|[01]?\\d?\\d)\\b");

    private final BrainProperties brainProperties;

    @Override
    public RailResult apply(RailContext context) {
        String input = context.sanitizedInput();
        if (input == null || input.isBlank()) return RailResult.pass(type());

        List<String> masked = new ArrayList<>();
        String sanitized = input;

        sanitized = mask(sanitized, SSN, "[PII-SSN]", masked);
        sanitized = mask(sanitized, JWT, "[PII-JWT]", masked);
        sanitized = mask(sanitized, CREDIT_CARD, "[PII-CREDIT-CARD]", masked);
        sanitized = mask(sanitized, IBAN, "[PII-IBAN]", masked);
        sanitized = mask(sanitized, EMAIL, "[PII-EMAIL]", masked);
        sanitized = mask(sanitized, PHONE, "[PII-PHONE]", masked);
        sanitized = mask(sanitized, IPV4, "[PII-IP]", masked);

        if (masked.isEmpty()) return RailResult.pass(type());

        boolean blockMode = brainProperties.guardrails() != null
                && brainProperties.guardrails().blockOnPiiDetection();

        if (blockMode) {
            return RailResult.block(type(), List.of("PII detected: " + masked.size() + " entities"));
        }

        log.debug("PII masked: {} entities in {}", masked.size(), context.sourceService());
        return RailResult.modify(type(), sanitized, masked);
    }

    private String mask(String input, Pattern pattern, String placeholder, List<String> masked) {
        Matcher m = pattern.matcher(input);
        if (!m.find()) return input;
        m.reset();
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            masked.add(placeholder);
            m.appendReplacement(sb, Matcher.quoteReplacement(placeholder));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @Override
    public RailPhase phase() { return RailPhase.PRE_LLM; }

    @Override
    public int priority() { return PRIORITY; }

    @Override
    public RailType type() { return RailType.PII; }
}
