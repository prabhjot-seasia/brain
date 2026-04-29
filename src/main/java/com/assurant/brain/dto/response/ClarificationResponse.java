package com.assurant.brain.dto.response;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Getter
@Log4j2
public class ClarificationResponse {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final boolean confident;
    private final Map<String, Object> dimensions;
    private final List<String> unknownReferences;
    private final List<ClarificationQuestion> questions;

    public record ClarificationQuestion(String text, List<String> options) {}

    private ClarificationResponse(boolean confident, Map<String, Object> dimensions,
                                   List<String> unknownReferences, List<ClarificationQuestion> questions) {
        this.confident = confident;
        this.dimensions = dimensions;
        this.unknownReferences = unknownReferences;
        this.questions = questions;
    }

    @SuppressWarnings("unchecked")
    public static ClarificationResponse fromRawJson(String json) {
        try {
            Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<>() {});
            boolean confident = Boolean.TRUE.equals(parsed.get("confident"));
            Map<String, Object> dimensions = (Map<String, Object>) parsed.getOrDefault("dimensions", Map.of());
            List<String> unknownRefs = (List<String>) parsed.getOrDefault("unknownReferences", List.of());

            List<ClarificationQuestion> questions = new ArrayList<>();
            Object rawQuestions = parsed.getOrDefault("questions", List.of());
            if (rawQuestions instanceof List<?> qList) {
                for (Object item : qList) {
                    if (item instanceof Map<?, ?> qMap) {
                        String text = String.valueOf(((Map<String, Object>) qMap).getOrDefault("text", ""));
                        List<String> options = new ArrayList<>();
                        Object rawOptions = ((Map<String, Object>) qMap).get("options");
                        if (rawOptions instanceof List<?> optList) {
                            for (Object opt : optList) {
                                options.add(String.valueOf(opt));
                            }
                        }
                        questions.add(new ClarificationQuestion(text, options));
                    } else if (item instanceof String s) {
                        questions.add(new ClarificationQuestion(s, List.of()));
                    }
                }
            }

            return new ClarificationResponse(confident, dimensions, unknownRefs, questions);
        } catch (Exception e) {
            log.error("Failed to parse clarifier response: {}", json, e);
            return new ClarificationResponse(false, Map.of(), Collections.emptyList(),
                    List.of(new ClarificationQuestion("Could not parse the requirement. Please rephrase and try again.", List.of())));
        }
    }

    public ClarificationResponse withConfident(boolean confidentOverride) {
        return new ClarificationResponse(confidentOverride, this.dimensions,
                this.unknownReferences, this.questions);
    }

    public double[] dimensionScores() {
        return new double[] {
                extractScore("why"),
                extractScore("what"),
                extractScore("where"),
                extractScore("how")
        };
    }

    @SuppressWarnings("unchecked")
    private double extractScore(String dim) {
        Object node = dimensions.get(dim);
        if (node instanceof Map<?, ?> map) {
            Object score = ((Map<String, Object>) map).get("score");
            if (score instanceof Number n) return n.doubleValue();
        }
        return 0.0;
    }
}
