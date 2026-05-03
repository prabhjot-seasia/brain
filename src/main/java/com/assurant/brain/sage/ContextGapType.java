package com.assurant.brain.sage;

public enum ContextGapType {
    SYMBOL_NOT_FOUND(1),
    CONFIG_PROVENANCE_UNKNOWN(1),
    CROSS_REPO_INTEGRATION(1),
    PROJECT_AFFINITY_LOW_CONFIDENCE(1),
    BDD_TAG_CONVENTION(2),
    LOGGING_CONVENTION(2),
    TEST_NAMING_CONVENTION(2),
    REVIEW_APPROVAL_RULES(2),
    CI_DEPLOY_TARGETS(2),
    RELEASE_STRATEGY(2),
    LIBRARY_CHOICE(3),
    PERFORMANCE_BUDGET(3),
    DATA_CLASSIFICATION(3),
    FEATURE_FLAG_PROVIDER(3),
    RATE_LIMIT_POLICY(3),
    ERROR_HANDLING_STYLE(3);

    private final int defaultTier;

    ContextGapType(int defaultTier) {
        this.defaultTier = defaultTier;
    }

    public int defaultTier() {
        return defaultTier;
    }
}
