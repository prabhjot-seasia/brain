package com.assurant.brain.jobs;

import java.util.Map;

public record JobEvent(String name, Map<String, Object> data) {

    public static JobEvent snapshot(AsyncJob job) {
        return new JobEvent("status", Map.of("job", job));
    }

    public static JobEvent progress(AsyncJob job) {
        return new JobEvent("progress", Map.of(
                "id", job.id(),
                "status", job.status(),
                "progressPct", job.progressPct() == null ? 0 : job.progressPct(),
                "progressMsg", job.progressMsg() == null ? "" : job.progressMsg()));
    }

    public static JobEvent terminal(AsyncJob job) {
        String name = switch (job.status()) {
            case SUCCEEDED -> "succeeded";
            case PARTIAL -> "partial";
            case FAILED -> "failed";
            case CANCELLED -> "cancelled";
            default -> "status";
        };
        return new JobEvent(name, Map.of("job", job));
    }

    public static JobEvent custom(String name, Map<String, Object> data) {
        return new JobEvent(name, data);
    }
}
