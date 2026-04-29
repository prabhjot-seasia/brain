package com.assurant.brain.learning;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("StructuralConventionAnalyzer")
class StructuralConventionAnalyzerTest {

    private StructuralConventionAnalyzer analyzer;

    @BeforeEach
    void setup() {
        analyzer = new StructuralConventionAnalyzer();
    }

    @Test
    @DisplayName("detects constructor injection followed when both have @RequiredArgsConstructor")
    void constructorInjectionFollowed() {
        String generated = "package com.ex;\nimport lombok.RequiredArgsConstructor;\n@RequiredArgsConstructor\npublic class Svc { private final String dep; }";
        String merged = "package com.ex;\nimport lombok.RequiredArgsConstructor;\n@RequiredArgsConstructor\npublic class Svc { private final String dep;\npublic void extra() {} }";
        StructuralConventionAnalyzer.AnalysisResult result = analyzer.analyze(
                Map.of("Svc.java", generated), Map.of("Svc.java", merged));
        assertThat(result.followed()).contains("constructor-injection");
    }

    @Test
    @DisplayName("detects constructor injection violated when generated lacks @RequiredArgsConstructor")
    void constructorInjectionViolated() {
        String generated = "package com.ex;\npublic class Svc { private final String dep; }";
        String merged = "package com.ex;\nimport lombok.RequiredArgsConstructor;\n@RequiredArgsConstructor\npublic class Svc { private final String dep; }";
        StructuralConventionAnalyzer.AnalysisResult result = analyzer.analyze(
                Map.of("Svc.java", generated), Map.of("Svc.java", merged));
        assertThat(result.violated()).contains("constructor-injection");
    }

    @Test
    @DisplayName("detects field injection violated when generated uses @Autowired")
    void fieldInjectionViolated() {
        String generated = "package com.ex;\nimport org.springframework.beans.factory.annotation.Autowired;\npublic class Svc { @Autowired private String dep; }";
        String merged = "package com.ex;\nimport lombok.RequiredArgsConstructor;\n@RequiredArgsConstructor\npublic class Svc { private final String dep; }";
        StructuralConventionAnalyzer.AnalysisResult result = analyzer.analyze(
                Map.of("Svc.java", generated), Map.of("Svc.java", merged));
        assertThat(result.violated()).contains("no-field-injection");
    }

    @Test
    @DisplayName("marks all conventions followed when files are identical")
    void identicalFiles() {
        String code = "package com.ex;\npublic class Svc {}";
        StructuralConventionAnalyzer.AnalysisResult result = analyzer.analyze(
                Map.of("Svc.java", code), Map.of("Svc.java", code));
        assertThat(result.followed()).anyMatch(f -> f.startsWith("all-conventions:"));
        assertThat(result.violated()).isEmpty();
    }

    @Test
    @DisplayName("skips non-Java files")
    void skipsNonJava() {
        StructuralConventionAnalyzer.AnalysisResult result = analyzer.analyze(
                Map.of("README.md", "# hi"), Map.of("README.md", "# hi"));
        assertThat(result.followed()).isEmpty();
        assertThat(result.violated()).isEmpty();
    }
}
