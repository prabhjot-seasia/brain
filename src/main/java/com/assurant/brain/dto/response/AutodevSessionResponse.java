package com.assurant.brain.dto.response;

import com.assurant.brain.dto.response.ClarificationResponse.ClarificationQuestion;

import java.util.List;
import java.util.Map;

public record AutodevSessionResponse(
        String sessionId,
        String intakeText,
        List<Map<String, Object>> proposedAffectedProjects,
        List<ClarificationQuestion> clarificationQuestions,
        boolean planReady
) {}
