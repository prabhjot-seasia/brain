package com.assurant.brain.service;

public record AffectedProject(
        String projectId,
        double confidence,
        String rationale
) {}
