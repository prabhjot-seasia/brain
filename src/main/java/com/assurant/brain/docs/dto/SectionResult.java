package com.assurant.brain.docs.dto;

import com.assurant.brain.enums.DocType;

public record SectionResult(DocType type, Status status, String content, String error) {

    public enum Status { OK, FAILED, PENDING }

    public static SectionResult ok(DocType type, String content) {
        return new SectionResult(type, Status.OK, content, null);
    }

    public static SectionResult failed(DocType type, String error) {
        return new SectionResult(type, Status.FAILED, null, error);
    }

    public static SectionResult pending(DocType type) {
        return new SectionResult(type, Status.PENDING, null, null);
    }
}
