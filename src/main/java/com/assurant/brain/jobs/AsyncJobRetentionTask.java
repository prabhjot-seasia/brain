package com.assurant.brain.jobs;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

@Log4j2
@Component
@RequiredArgsConstructor
public class AsyncJobRetentionTask {

    private static final List<AsyncJobStatus> TERMINAL = List.of(
            AsyncJobStatus.SUCCEEDED, AsyncJobStatus.FAILED,
            AsyncJobStatus.PARTIAL,   AsyncJobStatus.CANCELLED);

    private final AsyncJobRepository repository;

    @Value("${brain.jobs.retention-days:90}")
    private int retentionDays;

    @Scheduled(cron = "${brain.jobs.retention-cron:0 30 3 * * *}")
    @Transactional
    public void purgeExpired() {
        if (retentionDays <= 0) return;
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(retentionDays);
        int deleted = repository.deleteTerminalOlderThan(TERMINAL, cutoff);
        if (deleted > 0) {
            log.info("AsyncJob retention purged {} terminal row(s) older than {} days (cutoff={})",
                    deleted, retentionDays, cutoff);
        }
    }
}
