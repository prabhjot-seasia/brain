package com.assurant.brain.dto.request;

import jakarta.validation.constraints.NotBlank;
import org.hibernate.validator.constraints.URL;

public record CreatePrRequest(
        @NotBlank String sessionId,
        @NotBlank @URL String repoUrl,
        @NotBlank String baseBranch
) {}
