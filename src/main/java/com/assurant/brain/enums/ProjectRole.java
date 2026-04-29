package com.assurant.brain.enums;

public enum ProjectRole {
    OWNER,
    MEMBER,
    VIEWER;

    public boolean canRead() {
        return true;
    }

    public boolean canWrite() {
        return this == OWNER || this == MEMBER;
    }

    public boolean canAdminister() {
        return this == OWNER;
    }
}
