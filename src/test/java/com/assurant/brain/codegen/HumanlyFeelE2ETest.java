package com.assurant.brain.codegen;

import com.assurant.brain.avenger.MirageReviewer;
import com.assurant.brain.enums.AvengerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("UX-Q4 'humanly feel' end-to-end wiring")
class HumanlyFeelE2ETest {

    @Test
    @DisplayName("MIRAGE is in the Avenger roster (runFullReview iterates AvengerType.values())")
    void mirageInRoster() {
        boolean present = Arrays.stream(AvengerType.values()).anyMatch(t -> t == AvengerType.MIRAGE);
        assertThat(present).as("MIRAGE must be a first-class Avenger").isTrue();
        assertThat(AvengerType.values()).hasSize(12);
    }

    @Test
    @DisplayName("PrAnnotationsBuilder runs MIRAGE on a real candidate vs baseline + renders all 7 sections")
    void prBodyRunsMirageAndContainsAllSections() {
        StyleFingerprintBuilder fpBuilder = new StyleFingerprintBuilder();
        MirageReviewer mirage = new MirageReviewer(fpBuilder);
        PrDescriptionRenderer renderer = new PrDescriptionRenderer();
        VectorStore vectorStore = mock(VectorStore.class);

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
        when(vectorStore.similaritySearch(any(SearchRequest.class)))
                .thenReturn(List.of(new Document(tinyBaseline)));

        PrAnnotationsBuilder builder = new PrAnnotationsBuilder(mirage, vectorStore, renderer);

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

        String body = builder.renderPrBody("ce-imei", Map.of("B.java", hugeCandidate), "Add huge method");

        assertThat(body)
                .contains("### Scope")
                .contains("### Read-only references")
                .contains("### Conventions enforced")
                .contains("### Style fingerprint comparison")
                .contains("### Symbol grounding")
                .contains("### Blast radius")
                .contains("### Test coverage delta")
                .contains("### Rationale");

        assertThat(body)
                .as("MIRAGE must surface a non-APPROVED verdict for the obvious style mismatch")
                .containsAnyOf("REWRITE_TO_MATCH_HOUSE_STYLE", "FEELS_LIKE_LLM");

        assertThat(body)
                .as("PR body must NOT carry the legacy 'MIRAGE did not run' placeholder")
                .doesNotContain("MIRAGE did not run");
    }

    @Test
    @DisplayName("PrAnnotationsBuilder skips MIRAGE gracefully when baseline is empty (new project)")
    void newProjectStillRendersBody() {
        VectorStore vectorStore = mock(VectorStore.class);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of());
        PrAnnotationsBuilder builder = new PrAnnotationsBuilder(
                new MirageReviewer(new StyleFingerprintBuilder()),
                vectorStore,
                new PrDescriptionRenderer());

        String body = builder.renderPrBody("new-proj", Map.of("X.java", "public class X {}"), "init");

        assertThat(body).contains("### Scope").contains("### Rationale");
        assertThat(Set.of("APPROVED", "FEELS_LIKE_LLM", "REWRITE_TO_MATCH_HOUSE_STYLE"))
                .anyMatch(body::contains);
    }
}
