package com.assurant.brain.avenger;

import com.assurant.brain.codegen.StyleFingerprintBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MirageReviewer")
class MirageReviewerTest {

    private final MirageReviewer mirage = new MirageReviewer(new StyleFingerprintBuilder());

    @Test
    @DisplayName("empty generated files → APPROVED")
    void emptyFilesApproved() {
        var report = mirage.review(Map.of(), List.of());
        assertThat(report.verdict()).isEqualTo(MirageReviewer.Verdict.APPROVED);
    }

    @Test
    @DisplayName("empty baseline (project not ingested) → APPROVED with no signals")
    void emptyBaselineApproved() {
        var report = mirage.review(Map.of("X.java", "class X { void m() {} }"), List.of());
        assertThat(report.verdict()).isEqualTo(MirageReviewer.Verdict.APPROVED);
    }

    @Test
    @DisplayName("style match: candidate matches baseline → APPROVED")
    void candidateMatchesBaseline() {
        String tinyMethod = """
                package x;
                public class A {
                    public int run() { return 1; }
                    public int go()  { return 2; }
                }
                """;
        var report = mirage.review(Map.of("B.java", tinyMethod), List.of(tinyMethod));
        assertThat(report.verdict()).isEqualTo(MirageReviewer.Verdict.APPROVED);
        assertThat(report.signals()).isEmpty();
    }

    @Test
    @DisplayName("candidate methods 4σ off project avg → REWRITE_TO_MATCH_HOUSE_STYLE")
    void candidateRewriteWhenBlatantlyOff() {
        String tinyBaseline = """
                package x;
                public class A {
                    public int a() { return 1; }
                    public int b() { return 2; }
                    public int c() { return 3; }
                    public int d() { return 4; }
                    public int e() { return 5; }
                    public int f() { return 6; }
                }
                """;
        String hugeCandidate = """
                package x;
                public class B {
                    public int huge() {
                        int a = 1; int b = 2; int c = 3; int d = 4; int e = 5;
                        int f = 6; int g = 7; int h = 8; int i = 9; int j = 10;
                        int k = 11; int l = 12; int m = 13; int n = 14; int o = 15;
                        int p = 16; int q = 17; int r = 18; int s = 19; int t = 20;
                        int u = 21; int v = 22; int w = 23; int x = 24; int y = 25;
                        return a+b+c+d+e+f+g+h+i+j+k+l+m+n+o+p+q+r+s+t+u+v+w+x+y;
                    }
                }
                """;
        var report = mirage.review(Map.of("B.java", hugeCandidate), List.of(tinyBaseline));
        assertThat(report.verdict())
                .isIn(MirageReviewer.Verdict.REWRITE_TO_MATCH_HOUSE_STYLE,
                        MirageReviewer.Verdict.FEELS_LIKE_LLM);
        assertThat(report.signals()).anyMatch(s -> s.contains("method length"));
    }

    @Test
    @DisplayName("comments in code → flagged (codebase rule = 0 comments)")
    void commentsFlagged() {
        String tinyBaseline = """
                package x;
                public class A {
                    public int a() { return 1; }
                    public int b() { return 2; }
                }
                """;
        String withComments = """
                package x;
                public class B {
                    // this should not be here
                    /* block too */
                    public int run() { return 1; }
                    public int go()  { return 2; }
                }
                """;
        var report = mirage.review(Map.of("B.java", withComments), List.of(tinyBaseline));
        assertThat(report.signals()).anyMatch(s -> s.contains("comment density"));
    }
}
