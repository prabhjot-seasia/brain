package com.assurant.brain.codegen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConventionChecker")
class ConventionCheckerTest {

    private ConventionChecker checker;

    @BeforeEach
    void setup() {
        checker = new ConventionChecker();
    }

    @Test
    @DisplayName("passes code following all conventions")
    void cleanCode() {
        String code = """
                package com.example;

                import lombok.RequiredArgsConstructor;
                import lombok.extern.log4j.Log4j2;

                @Log4j2
                @RequiredArgsConstructor
                public class MyService {
                    private final String dependency;
                }
                """;
        List<String> violations = checker.check(Map.of("src/MyService.java", code));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("flags @Autowired field injection")
    void flagsAutowired() {
        String code = """
                package com.example;

                import org.springframework.beans.factory.annotation.Autowired;

                public class MyService {
                    @Autowired
                    private String dependency;
                }
                """;
        List<String> violations = checker.check(Map.of("src/MyService.java", code));
        assertThat(violations).anyMatch(v -> v.contains("Field injection") && v.contains("@Autowired"));
    }

    @Test
    @DisplayName("flags missing @RequiredArgsConstructor with final fields")
    void flagsMissingConstructorAnnotation() {
        String code = """
                package com.example;

                public class MyService {
                    private final String dependency;
                }
                """;
        List<String> violations = checker.check(Map.of("src/MyService.java", code));
        assertThat(violations).anyMatch(v -> v.contains("@RequiredArgsConstructor"));
    }

    @Test
    @DisplayName("flags @Slf4j usage")
    void flagsSlf4j() {
        String code = """
                package com.example;

                import lombok.extern.slf4j.Slf4j;

                @Slf4j
                public class MyService {}
                """;
        List<String> violations = checker.check(Map.of("src/MyService.java", code));
        assertThat(violations).anyMatch(v -> v.contains("@Slf4j") && v.contains("@Log4j2"));
    }

    @Test
    @DisplayName("flags raw String status field")
    void flagsRawStringStatus() {
        String code = """
                package com.example;

                public class MyEntity {
                    private String status;
                }
                """;
        List<String> violations = checker.check(Map.of("src/MyEntity.java", code));
        assertThat(violations).anyMatch(v -> v.contains("status") && v.contains("enum"));
    }

    @Test
    @DisplayName("skips unparseable files without error")
    void skipsUnparseable() {
        List<String> violations = checker.check(Map.of("src/Broken.java", "not valid java {{{"));
        assertThat(violations).isEmpty();
    }
}
