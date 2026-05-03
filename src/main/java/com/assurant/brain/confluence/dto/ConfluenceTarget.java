package com.assurant.brain.confluence.dto;

import jakarta.validation.constraints.NotBlank;

public record ConfluenceTarget(
        @NotBlank String spaceKey,
        @NotBlank String parentPageId) {

    public boolean isComplete() {
        return spaceKey != null && !spaceKey.isBlank()
                && parentPageId != null && !parentPageId.isBlank();
    }
}
