package com.assurant.brain.jobs;

public enum AsyncJobStatus {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    PARTIAL,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == PARTIAL || this == FAILED || this == CANCELLED;
    }

    public boolean isInFlight() {
        return this == QUEUED || this == RUNNING;
    }
}
