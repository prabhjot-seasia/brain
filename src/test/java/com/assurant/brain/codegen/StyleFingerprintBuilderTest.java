package com.assurant.brain.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StyleFingerprintBuilder")
class StyleFingerprintBuilderTest {

    private final StyleFingerprintBuilder builder = new StyleFingerprintBuilder();

    @Test
    @DisplayName("empty input → EMPTY fingerprint")
    void emptyInput() {
        assertThat(builder.build(List.of())).isSameAs(StyleFingerprint.EMPTY);
        assertThat(builder.build(null)).isSameAs(StyleFingerprint.EMPTY);
    }

    @Test
    @DisplayName("computes avg method length, stdev, p90 across sampled files")
    void computesMethodLengthStats() {
        String src = """
                package x;
                public class Sample {
                    public int small() { return 1; }
                    public int medium() {
                        int a = 1;
                        int b = 2;
                        return a + b;
                    }
                    public int large() {
                        int a = 1;
                        int b = 2;
                        int c = 3;
                        int d = 4;
                        int e = 5;
                        int f = 6;
                        return a + b + c + d + e + f;
                    }
                }
                """;
        StyleFingerprint fp = builder.build(List.of(src));
        assertThat(fp.sampledMethods()).isEqualTo(3);
        assertThat(fp.avgMethodLines()).isBetween(1.0, 15.0);
        assertThat(fp.p90MethodLines()).isGreaterThanOrEqualTo((int) fp.avgMethodLines());
    }

    @Test
    @DisplayName("detects return-early ratio (methods with >=2 returns)")
    void returnEarlyDetected() {
        String src = """
                package x;
                public class R {
                    public int guarded(String s) {
                        if (s == null) return -1;
                        return s.length();
                    }
                    public int single(String s) {
                        return s == null ? -1 : s.length();
                    }
                }
                """;
        StyleFingerprint fp = builder.build(List.of(src));
        assertThat(fp.returnEarlyRatio()).isBetween(0.4, 0.6);
    }

    @Test
    @DisplayName("detects var, stream, lambda usage")
    void detectsVarStreamLambda() {
        String src = """
                package x;
                import java.util.List;
                public class Modern {
                    public long count(List<String> items) {
                        var list = items;
                        return list.stream().filter(s -> s.length() > 0).count();
                    }
                }
                """;
        StyleFingerprint fp = builder.build(List.of(src));
        assertThat(fp.varUsageRatio()).isEqualTo(1.0);
        assertThat(fp.streamUsageRatio()).isEqualTo(1.0);
        assertThat(fp.lambdaUsageRatio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("renderForPrompt produces a HOUSE STYLE block when not empty")
    void rendersHouseStyle() {
        String src = """
                package x;
                public class Tiny { public int run() { return 1; } }
                """;
        StyleFingerprint fp = builder.build(List.of(src));
        String rendered = fp.renderForPrompt();
        assertThat(rendered)
                .contains("HOUSE STYLE")
                .contains("avg method length")
                .contains("return-early ratio");
    }

    @Test
    @DisplayName("EMPTY renders a guarded fallback")
    void emptyRenderFallback() {
        String rendered = StyleFingerprint.EMPTY.renderForPrompt();
        assertThat(rendered).contains("no style fingerprint available");
    }

    @Test
    @DisplayName("sigmaOff measures distance from baseline avg in stdevs")
    void sigmaOff() {
        StyleFingerprint baseline = new StyleFingerprint(50, 12d, 6d, 20, 0.4, 0.3, 0.4, 0.5, 8, 0d);
        assertThat(builder.sigmaOff(12, baseline)).isEqualTo(0d);
        assertThat(builder.sigmaOff(24, baseline)).isEqualTo(2d);
    }

    @Test
    @DisplayName("malformed Java is skipped without crashing the builder")
    void malformedSkipped() {
        StyleFingerprint fp = builder.build(List.of("this is not java {{{"));
        assertThat(fp).isSameAs(StyleFingerprint.EMPTY);
    }
}
