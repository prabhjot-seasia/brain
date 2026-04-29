package com.assurant.brain.dto.request;

import jakarta.validation.constraints.NotBlank;

public record AnalyzeRequest(
        String projectId,
        @NotBlank String requirement,
        String sessionId,
        String answers
) {}
