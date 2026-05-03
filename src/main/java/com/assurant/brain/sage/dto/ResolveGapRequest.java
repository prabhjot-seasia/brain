package com.assurant.brain.sage.dto;

import jakarta.validation.constraints.NotBlank;

public record ResolveGapRequest(
        @NotBlank String gapSignature,
        @NotBlank String answerKind,
        String answerValue) {}
