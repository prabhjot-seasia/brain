package com.assurant.brain.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TokenEstimator")
class TokenEstimatorTest {

    @Test
    @DisplayName("estimates tokens at 4 chars per token")
    void estimateBasic() {
        assertThat(TokenEstimator.estimate("abcdefgh")).isEqualTo(2);
    }

    @Test
    @DisplayName("handles null and empty input")
    void estimateNullAndEmpty() {
        assertThat(TokenEstimator.estimate((String) null)).isEqualTo(0);
        assertThat(TokenEstimator.estimate("")).isEqualTo(0);
    }

    @Test
    @DisplayName("estimates multiple segments")
    void estimateMultiple() {
        int result = TokenEstimator.estimate(List.of("abcd", "efgh"));
        assertThat(result).isEqualTo(2);
    }

    @Test
    @DisplayName("fitsWithinBudget returns true when under budget")
    void fitsUnder() {
        assertThat(TokenEstimator.fitsWithinBudget("abcdefgh", 5)).isTrue();
    }

    @Test
    @DisplayName("fitsWithinBudget returns false when over budget")
    void fitsOver() {
        assertThat(TokenEstimator.fitsWithinBudget("abcdefgh", 1)).isFalse();
    }

    @Test
    @DisplayName("truncateToTokenBudget truncates long text")
    void truncate() {
        String result = TokenEstimator.truncateToTokenBudget("a".repeat(100), 10);
        assertThat(result).hasSize(40);
    }

    @Test
    @DisplayName("truncateToTokenBudget returns full text when within budget")
    void truncateNoOp() {
        String result = TokenEstimator.truncateToTokenBudget("short", 100);
        assertThat(result).isEqualTo("short");
    }
}
