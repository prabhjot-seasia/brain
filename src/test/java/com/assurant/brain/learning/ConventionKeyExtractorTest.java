package com.assurant.brain.learning;

import com.assurant.brain.config.properties.BrainProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConventionKeyExtractor")
class ConventionKeyExtractorTest {

    private final ConventionKeyExtractor extractor = new ConventionKeyExtractor(
            new BrainProperties(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null));

    @Test
    @DisplayName("returns empty for null input")
    void handlesNull() {
        assertThat(extractor.extract(null)).isEmpty();
    }

    @Test
    @DisplayName("detects constructor-injection")
    void detectsConstructorInjection() {
        assertThat(extractor.extract("Use @RequiredArgsConstructor for DI"))
                .isEqualTo("constructor-injection");
    }

    @Test
    @DisplayName("detects log4j2-usage for Slf4j mentions")
    void detectsLog4j2() {
        assertThat(extractor.extract("Uses @Slf4j — must use @Log4j2")).isEqualTo("log4j2-usage");
    }

    @Test
    @DisplayName("detects no-field-injection")
    void detectsFieldInjection() {
        assertThat(extractor.extract("Field injection (@Autowired) detected"))
                .isEqualTo("no-field-injection");
    }

    @Test
    @DisplayName("detects pii-leak")
    void detectsPiiLeak() {
        assertThat(extractor.extract("LLM response leaked SSN to output"))
                .isEqualTo("pii-leak");
    }

    @Test
    @DisplayName("detects prompt-injection")
    void detectsPromptInjection() {
        assertThat(extractor.extract("Prompt injection pattern matched"))
                .isEqualTo("prompt-injection");
    }

    @Test
    @DisplayName("truncates unknown rules to 30 chars")
    void truncatesUnknown() {
        String longRule = "a".repeat(100);
        assertThat(extractor.extract(longRule)).hasSize(30);
    }
}
