package com.assurant.brain.ingest;

public enum ContextGapType {
    CONFIG_TABLE_DATA,
    SECRET_VALUE_NEEDED,
    ENV_SPECIFIC_VALUE,
    SCHEMA_GAP,
    CONTRACT_MISSING,
    UNCOVERED_DIMENSION
}
