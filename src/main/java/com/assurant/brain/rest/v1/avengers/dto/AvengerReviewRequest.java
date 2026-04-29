package com.assurant.brain.rest.v1.avengers.dto;

import jakarta.validation.constraints.NotBlank;

public record AvengerReviewRequest(
        String projectId,
        @NotBlank String code,
        String context
) {}
