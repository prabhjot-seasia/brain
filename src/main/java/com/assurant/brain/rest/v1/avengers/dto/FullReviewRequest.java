package com.assurant.brain.rest.v1.avengers.dto;

import jakarta.validation.constraints.NotBlank;

public record FullReviewRequest(
        String projectId,
        @NotBlank String code,
        String context
) {}
