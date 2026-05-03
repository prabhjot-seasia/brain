package com.assurant.brain.avenger.checks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ItControllerEntryCheckTest {

    private final ItControllerEntryCheck check = new ItControllerEntryCheck();

    @Test
    @DisplayName("clean IT using MockMvc against real controller passes")
    void cleanItPasses() {
        String code = """
                package com.example;
                import org.springframework.boot.test.context.SpringBootTest;
                import org.springframework.test.web.servlet.MockMvc;
                @SpringBootTest
                class FooControllerIT {
                    private MockMvc mvc;
                }
                """;
        var result = check.check(code);
        assertThat(result.violation()).isFalse();
    }

    @Test
    @DisplayName("@MockBean on a *Controller is BLOCKED")
    void mockBeanControllerBlocked() {
        String code = """
                package com.example;
                import org.springframework.boot.test.mock.mockito.MockBean;
                class FooIT {
                    @MockBean FooController controller;
                }
                """;
        var result = check.check(code);
        assertThat(result.violation()).isTrue();
        assertThat(result.findings()).anyMatch(f -> f.contains("FooController"));
    }

    @Test
    @DisplayName("new SomethingController(...) inside a test is BLOCKED")
    void instantiatedControllerBlocked() {
        String code = """
                package com.example;
                class FooIT {
                    void run() { var c = new BarController(); c.handle(); }
                }
                """;
        var result = check.check(code);
        assertThat(result.violation()).isTrue();
        assertThat(result.findings()).anyMatch(f -> f.contains("BarController"));
    }

    @Test
    @DisplayName("blank or unparseable input is treated as clean")
    void blankIsClean() {
        assertThat(check.check(null).violation()).isFalse();
        assertThat(check.check("").violation()).isFalse();
        assertThat(check.check("not java at all { broken").violation()).isFalse();
    }
}
