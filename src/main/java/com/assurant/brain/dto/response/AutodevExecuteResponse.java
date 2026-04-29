package com.assurant.brain.dto.response;

import java.util.List;
import java.util.Map;

public record AutodevExecuteResponse(
        String sessionId,
        List<Map<String, Object>> perProject
) {}
