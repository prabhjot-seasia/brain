package com.assurant.brain.dto.request;

public record ProposedTicket(
        String title,
        String description,
        String acceptanceCriteria,
        String issueType,
        int storyPoints,
        String priority
) {}
