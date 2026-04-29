package com.assurant.brain.facade.autodev;

import com.assurant.brain.enums.BatchStatus;

import java.util.List;
import java.util.UUID;

public record MultiRepoPrResult(
        UUID batchId,
        BatchStatus overallStatus,
        int totalRepos,
        int succeeded,
        int failed,
        int skipped,
        List<PerRepoResult> perRepo
) {}
