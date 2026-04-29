package com.assurant.brain.docs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("FullDocRateLimiter")
class FullDocRateLimiterTest {

    @Test
    @DisplayName("first call against a fresh key passes")
    void firstCallPasses() {
        FullDocRateLimiter limiter = new FullDocRateLimiter();
        assertThatNoException().isThrownBy(() -> limiter.check("full:proj-A"));
    }

    @Test
    @DisplayName("second call within 15 s minimum interval is rejected")
    void minIntervalEnforced() {
        FullDocRateLimiter limiter = new FullDocRateLimiter();
        limiter.check("full:proj-A");
        assertThatThrownBy(() -> limiter.check("full:proj-A"))
                .isInstanceOf(FullDocRateLimiter.RateLimitExceededException.class)
                .hasMessageContaining("15");
    }

    @Test
    @DisplayName("different keys do not interfere with each other")
    void keysAreIndependent() {
        FullDocRateLimiter limiter = new FullDocRateLimiter();
        limiter.check("full:proj-A");
        assertThatNoException().isThrownBy(() -> limiter.check("full:proj-B"));
        assertThatNoException().isThrownBy(() -> limiter.check("retry:doc-1"));
    }

    @Test
    @DisplayName("RateLimitExceededException carries human-readable diagnostic")
    void exceptionMessageIsHelpful() {
        FullDocRateLimiter limiter = new FullDocRateLimiter();
        limiter.check("full:proj-A");
        try {
            limiter.check("full:proj-A");
        } catch (FullDocRateLimiter.RateLimitExceededException e) {
            assertThat(e.getMessage()).contains("full:proj-A");
        }
    }
}
