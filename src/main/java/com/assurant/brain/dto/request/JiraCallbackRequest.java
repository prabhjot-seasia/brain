package com.assurant.brain.dto.request;

import jakarta.validation.constraints.NotBlank;

public record JiraCallbackRequest(
        @NotBlank String code,
        String userId
) {
    public String effectiveUserId() {
        return userId != null && !userId.isBlank() ? userId : "default";
    }
}
