package com.assurant.brain.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EditBoundaryEnforcer")
class EditBoundaryEnforcerTest {

    private final EditBoundaryEnforcer enforcer = new EditBoundaryEnforcer();

    @Test
    @DisplayName("unbounded boundary → ok, no violations")
    void unboundedOk() {
        var result = enforcer.enforce(EditBoundary.unbounded(),
                Map.of("A.java", "old"),
                Map.of("A.java", "new", "B.java", "totally new"));
        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("file outside mayTouchFiles → violation names the offending file")
    void outOfBoundsFileFlagged() {
        EditBoundary boundary = new EditBoundary(List.of("A.java"), null, null, null);
        var result = enforcer.enforce(boundary,
                Map.of("A.java", "x"),
                Map.of("A.java", "x", "B.java", "scope creep"));
        assertThat(result.ok()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.contains("B.java"))
                .anyMatch(v -> v.contains("mayTouchFiles"));
    }

    @Test
    @DisplayName("edit-ratio > cap → violation with measured pct")
    void editRatioExceeded() {
        EditBoundary boundary = new EditBoundary(List.of("A.java"), null, null, 30);
        String previous = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10";
        String generated = "line1\nline2\nNEW3\nNEW4\nNEW5\nNEW6\nNEW7\nNEW8\nline9\nline10";
        var result = enforcer.enforce(boundary,
                Map.of("A.java", previous),
                Map.of("A.java", generated));
        assertThat(result.ok()).isFalse();
        assertThat(result.violations())
                .anyMatch(v -> v.contains("edit-ratio"))
                .anyMatch(v -> v.contains("scope creep"));
    }

    @Test
    @DisplayName("edit-ratio at or under cap → ok")
    void editRatioWithinCap() {
        EditBoundary boundary = new EditBoundary(List.of("A.java"), null, null, 30);
        String previous = "line1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\nline10";
        String generated = "line1\nline2\nNEW3\nline4\nline5\nline6\nline7\nline8\nline9\nline10";
        var result = enforcer.enforce(boundary,
                Map.of("A.java", previous),
                Map.of("A.java", generated));
        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("mustNotTouch symbol referenced in output → violation")
    void mustNotTouchSymbolFlagged() {
        EditBoundary boundary = new EditBoundary(
                List.of("A.java"),
                null,
                List.of("PublicApiContract.criticalMethod"),
                100);
        var result = enforcer.enforce(boundary,
                Map.of("A.java", "old"),
                Map.of("A.java", "PublicApiContract.criticalMethod(args);"));
        assertThat(result.ok()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.contains("PublicApiContract.criticalMethod"));
    }

    @Test
    @DisplayName("new file (no previous content) inside mayTouchFiles → ok (edit-ratio is for modifications, not creations)")
    void newFileInsideAllowlistOk() {
        EditBoundary boundary = new EditBoundary(List.of("New.java"), null, null, 30);
        var result = enforcer.enforce(boundary,
                Map.of(),
                Map.of("New.java", "package x; class New {}"));
        assertThat(result.ok()).isTrue();
    }

    @Test
    @DisplayName("new file OUTSIDE mayTouchFiles → flagged regardless of ratio")
    void newFileOutsideAllowlistFlagged() {
        EditBoundary boundary = new EditBoundary(List.of("Allowed.java"), null, null, 30);
        var result = enforcer.enforce(boundary,
                Map.of(),
                Map.of("Sneak.java", "package x; class Sneak {}"));
        assertThat(result.ok()).isFalse();
        assertThat(result.violations()).anyMatch(v -> v.contains("Sneak.java"));
    }

    @Test
    @DisplayName("empty generated map → ok (no-op)")
    void emptyGeneratedOk() {
        EditBoundary boundary = new EditBoundary(List.of("A.java"), null, null, 30);
        var result = enforcer.enforce(boundary, Map.of("A.java", "x"), Map.of());
        assertThat(result.ok()).isTrue();
    }
}
