package com.assurant.brain.confluence.dto;

public record PublishResult(
        Status status,
        String pageId,
        String url,
        int versionNumber,
        int attachmentsUploaded,
        String message) {

    public enum Status { CREATED, UPDATED, PARTIAL, SKIPPED, FAILED }

    public static PublishResult skipped(String reason) {
        return new PublishResult(Status.SKIPPED, null, null, 0, 0, reason);
    }

    public static PublishResult failed(String reason) {
        return new PublishResult(Status.FAILED, null, null, 0, 0, reason);
    }
}
