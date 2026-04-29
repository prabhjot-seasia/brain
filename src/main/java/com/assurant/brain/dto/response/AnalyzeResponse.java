package com.assurant.brain.dto.response;

import com.assurant.brain.dto.response.ClarificationResponse.ClarificationQuestion;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

@Getter
@ToString
public class AnalyzeResponse {

    private final String sessionId;
    private final boolean planReady;

    private final String plan;

    private final List<ClarificationQuestion> questions;
    private final List<String> unknownReferences;
    private final Map<String, Object> dimensions;

    private final boolean forcedAfterMaxRounds;

    private final ResponseKind kind;

    private final List<Map<String, Object>> affectedProjects;

    public enum ResponseKind { IMPLEMENT, EXPLAIN }

    private AnalyzeResponse(String sessionId, boolean planReady, String plan,
                             List<ClarificationQuestion> questions, List<String> unknownReferences,
                             Map<String, Object> dimensions,
                             boolean forcedAfterMaxRounds, ResponseKind kind,
                             List<Map<String, Object>> affectedProjects) {
        this.sessionId = sessionId;
        this.planReady = planReady;
        this.plan = plan;
        this.questions = questions;
        this.unknownReferences = unknownReferences;
        this.dimensions = dimensions;
        this.forcedAfterMaxRounds = forcedAfterMaxRounds;
        this.kind = kind;
        this.affectedProjects = affectedProjects;
    }

    public static AnalyzeResponse withPlan(String sessionId, String plan) {
        return new AnalyzeResponse(sessionId, true, plan, null, null, null,
                false, ResponseKind.IMPLEMENT, null);
    }

    public static AnalyzeResponse withForcedPlan(String sessionId, String plan) {
        return new AnalyzeResponse(sessionId, true, plan, null, null, null,
                true, ResponseKind.IMPLEMENT, null);
    }

    public static AnalyzeResponse withExplanation(String sessionId, String explanationMarkdown) {
        return new AnalyzeResponse(sessionId, true, explanationMarkdown, null, null, null,
                false, ResponseKind.EXPLAIN, null);
    }

    public static AnalyzeResponse needsClarification(String sessionId, List<ClarificationQuestion> questions,
                                                      List<String> unknownReferences,
                                                      Map<String, Object> dimensions) {
        return new AnalyzeResponse(sessionId, false, null, questions, unknownReferences, dimensions,
                false, ResponseKind.IMPLEMENT, null);
    }

    public AnalyzeResponse withAffectedProjects(List<Map<String, Object>> affectedProjects) {
        return new AnalyzeResponse(sessionId, planReady, plan, questions, unknownReferences,
                dimensions, forcedAfterMaxRounds, kind, affectedProjects);
    }
}
