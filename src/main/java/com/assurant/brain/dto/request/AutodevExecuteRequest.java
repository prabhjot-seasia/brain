package com.assurant.brain.dto.request;

import java.util.List;

public record AutodevExecuteRequest(
        String sessionId,
        List<String> approvedProjectIds
) {}
