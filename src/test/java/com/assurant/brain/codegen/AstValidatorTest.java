package com.assurant.brain.codegen;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AstValidator")
class AstValidatorTest {

    private AstValidator validator;

    @BeforeEach
    void setup() {
        validator = new AstValidator();
    }

    @Test
    @DisplayName("passes valid Java code")
    void validCode() {
        String code = "package com.example;\n\npublic class Foo {\n    public void doSomething() {}\n}";
        AstValidator.ValidationResult result = validator.validate(Map.of("src/Foo.java", code));
        assertThat(result.passed()).isTrue();
        assertThat(result.issues()).isEmpty();
    }

    @Test
    @DisplayName("fails on unparseable syntax")
    void syntaxError() {
        String code = "package com.example;\n\npublic class { broken";
        AstValidator.ValidationResult result = validator.validate(Map.of("src/Foo.java", code));
        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.contains("[SYNTAX]"));
    }

    @Test
    @DisplayName("flags lowercase class name")
    void lowercaseClassName() {
        String code = "package com.example;\n\npublic class myClass {}";
        AstValidator.ValidationResult result = validator.validate(Map.of("src/myClass.java", code));
        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.contains("[NAMING]") && i.contains("PascalCase"));
    }

    @Test
    @DisplayName("flags comments in generated code")
    void detectsComments() {
        String code = "package com.example;\n\n// This is a comment\npublic class Foo {}";
        AstValidator.ValidationResult result = validator.validate(Map.of("src/Foo.java", code));
        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.contains("[COMMENT]"));
    }

    @Test
    @DisplayName("flags non-uppercase constant names")
    void nonUppercaseConstant() {
        String code = "package com.example;\n\npublic class Foo {\n    private static final String myConst = \"x\";\n}";
        AstValidator.ValidationResult result = validator.validate(Map.of("src/Foo.java", code));
        assertThat(result.passed()).isFalse();
        assertThat(result.issues()).anyMatch(i -> i.contains("[NAMING]") && i.contains("UPPER_SNAKE_CASE"));
    }

    @Test
    @DisplayName("skips non-Java files")
    void skipsNonJava() {
        AstValidator.ValidationResult result = validator.validate(Map.of("README.md", "# Hello"));
        assertThat(result.passed()).isTrue();
    }

    @Test
    @DisplayName("allows log and serialVersionUID as static final names")
    void allowsExemptions() {
        String code = "package com.example;\n\npublic class Foo {\n    private static final long serialVersionUID = 1L;\n}";
        AstValidator.ValidationResult result = validator.validate(Map.of("src/Foo.java", code));
        assertThat(result.passed()).isTrue();
    }
}
