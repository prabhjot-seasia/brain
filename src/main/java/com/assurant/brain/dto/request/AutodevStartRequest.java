package com.assurant.brain.dto.request;

public record AutodevStartRequest(
        String source,
        String payload,
        String intakeId,
        String seedProjectId
) {}
