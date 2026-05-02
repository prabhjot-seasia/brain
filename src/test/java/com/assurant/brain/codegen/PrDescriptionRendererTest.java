package com.assurant.brain.codegen;

import com.assurant.brain.avenger.MirageReviewer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PrDescriptionRenderer")
class PrDescriptionRendererTest {

    private final PrDescriptionRenderer renderer = new PrDescriptionRenderer();

    @Test
    @DisplayName("renders all 7 structured sections in order")
    void rendersAllSections() {
        var annotations = new PrDescriptionRenderer.PrAnnotations(
                Map.of("A.java", "old"),
                Map.of("A.java", "new\nline2"),
                Set.of("B.java"),
                List.of("@Log4j2 not @Slf4j", "@RequiredArgsConstructor"),
                new MirageReviewer.Report(MirageReviewer.Verdict.APPROVED, List.of(), 0.8),
                List.of(new SeamAnalyzer.CallerSite("ce-imei", "com.x.Caller", "src/Caller.java")),
                3, 42,
                "Add retry policy to payment service.",
                "ADD_RETRY_POLICY");

        String body = renderer.render(annotations);

        assertThat(body)
                .contains("### Scope")
                .contains("### Read-only references")
                .contains("### Conventions enforced")
                .contains("### Style fingerprint comparison")
                .contains("### Symbol grounding")
                .contains("### Blast radius")
                .contains("### Test coverage delta")
                .contains("### Rationale");

        int scopeIdx = body.indexOf("### Scope");
        int rationaleIdx = body.indexOf("### Rationale");
        assertThat(scopeIdx).isLessThan(rationaleIdx);
    }

    @Test
    @DisplayName("scope shows file paths + line deltas")
    void scopeShowsLineDeltas() {
        var annotations = baseAnnotations(
                Map.of(),
                Map.of("New.java", "package x;\nclass New {}\n"));
        String body = renderer.render(annotations);
        assertThat(body).contains("`New.java`").contains("+");
    }

    @Test
    @DisplayName("blast radius computes risk tier")
    void blastRadiusRisk() {
        var ten = new java.util.ArrayList<SeamAnalyzer.CallerSite>();
        for (int i = 0; i < 12; i++) {
            ten.add(new SeamAnalyzer.CallerSite("p", "C" + i, "src/C" + i + ".java"));
        }
        var annotations = new PrDescriptionRenderer.PrAnnotations(
                Map.of(), Map.of("A.java", "x"), Set.of(), List.of("@Log4j2"),
                new MirageReviewer.Report(MirageReviewer.Verdict.APPROVED, List.of(), 0d),
                ten, 0, 0, "rationale.", "summary");
        String body = renderer.render(annotations);
        assertThat(body).contains("HIGH");
    }

    @Test
    @DisplayName("MIRAGE FEELS_LIKE_LLM lists signals")
    void mirageSignalsRendered() {
        var report = new MirageReviewer.Report(
                MirageReviewer.Verdict.FEELS_LIKE_LLM,
                List.of("method length: candidate avg 25.0 vs baseline 12.0 ± 3.0 (4.3σ off)"),
                4.3);
        var annotations = new PrDescriptionRenderer.PrAnnotations(
                Map.of(), Map.of("A.java", "x"), Set.of(), List.of("@Log4j2"),
                report, List.of(), 0, 0, "rationale.", "summary");
        String body = renderer.render(annotations);
        assertThat(body).contains("FEELS_LIKE_LLM").contains("method length");
    }

    @Test
    @DisplayName("empty rationale shows fallback message")
    void emptyRationaleFallback() {
        var annotations = new PrDescriptionRenderer.PrAnnotations(
                Map.of(), Map.of("A.java", "x"), Set.of(), List.of(),
                new MirageReviewer.Report(MirageReviewer.Verdict.APPROVED, List.of(), 0d),
                List.of(), 0, 0, "", "summary");
        String body = renderer.render(annotations);
        assertThat(body).contains("(none provided)");
    }

    private PrDescriptionRenderer.PrAnnotations baseAnnotations(
            Map<String, String> previous, Map<String, String> generated) {
        return new PrDescriptionRenderer.PrAnnotations(
                previous, generated, Set.of(),
                List.of("@Log4j2"),
                new MirageReviewer.Report(MirageReviewer.Verdict.APPROVED, List.of(), 0d),
                List.of(), 0, 0,
                "rationale.", "summary");
    }
}
