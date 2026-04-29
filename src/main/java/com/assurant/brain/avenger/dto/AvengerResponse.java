package com.assurant.brain.avenger.dto;

import com.assurant.brain.enums.AvengerType;
import com.assurant.brain.enums.AvengerVerdict;

import java.util.List;
import java.util.UUID;

public record AvengerResponse(
        UUID reviewId,
        AvengerType avenger,
        AvengerVerdict verdict,
        List<String> issues,
        String summary,
        long latencyMs
) {}
