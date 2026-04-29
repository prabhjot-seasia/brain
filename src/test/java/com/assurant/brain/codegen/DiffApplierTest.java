package com.assurant.brain.codegen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("DiffApplier")
class DiffApplierTest {

    private DiffApplier applier;

    @BeforeEach
    void setup() {
        applier = new DiffApplier();
    }

    @Test
    @DisplayName("applies simple addition")
    void applyAddition() {
        String original = "line1\nline2\nline3";
        String diff = """
                --- a/file.java
                +++ b/file.java
                @@ -1,3 +1,4 @@
                 line1
                +inserted
                 line2
                 line3
                """;
        String result = applier.apply(original, diff);
        assertThat(result).contains("inserted");
        assertThat(result.split("\n")).hasSize(4);
    }

    @Test
    @DisplayName("applies simple removal")
    void applyRemoval() {
        String original = "line1\nline2\nline3";
        String diff = """
                --- a/file.java
                +++ b/file.java
                @@ -1,3 +1,2 @@
                 line1
                -line2
                 line3
                """;
        String result = applier.apply(original, diff);
        assertThat(result).doesNotContain("line2");
        assertThat(result.split("\n")).hasSize(2);
    }

    @Test
    @DisplayName("returns original when no hunks found")
    void noHunks() {
        String original = "line1\nline2";
        String result = applier.apply(original, "no diff here");
        assertThat(result).isEqualTo(original);
    }

    @Test
    @DisplayName("applies replacement (remove + add)")
    void applyReplacement() {
        String original = "line1\nold\nline3";
        String diff = """
                --- a/file.java
                +++ b/file.java
                @@ -1,3 +1,3 @@
                 line1
                -old
                +new
                 line3
                """;
        String result = applier.apply(original, diff);
        assertThat(result).contains("new");
        assertThat(result).doesNotContain("old");
    }
}
