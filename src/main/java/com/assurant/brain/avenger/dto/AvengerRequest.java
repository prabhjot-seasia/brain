package com.assurant.brain.avenger.dto;

import com.assurant.brain.enums.AvengerType;

public record AvengerRequest(
        AvengerType avenger,
        String projectId,
        String code,
        String context
) {}
