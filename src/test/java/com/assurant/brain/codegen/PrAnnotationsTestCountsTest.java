package com.assurant.brain.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PrAnnotationsBuilder — real test counts (kills the +0/+0 lie)")
class PrAnnotationsTestCountsTest {

    @Test
    @DisplayName("counts *Test.java, *IT.java, *.test.tsx, *.feature as test files")
    void countsTestFilesByExtension() {
        Map<String, String> files = Map.of(
                "src/main/java/com/x/Foo.java", "package com.x; class Foo {}",
                "src/test/java/com/x/FooTest.java", "package com.x;\nimport org.junit.*;\n@Test\nvoid t() { }",
                "src/test/java/com/x/FooIT.java", "package com.x;\nint a = 1;",
                "ui/src/Foo.test.tsx", "import 'x';\ntest('a', () => {});",
                "features/login.feature", "Feature: F\nScenario: S\nGiven x");

        PrAnnotationsBuilder.TestCounts counts = PrAnnotationsBuilder.countTests(files);

        assertThat(counts.testFiles()).isEqualTo(4);
        assertThat(counts.coveredLines()).isGreaterThan(0);
    }

    @Test
    @DisplayName("non-test files contribute zero")
    void nonTestFilesIgnored() {
        PrAnnotationsBuilder.TestCounts counts = PrAnnotationsBuilder.countTests(Map.of(
                "src/main/java/com/x/Foo.java", "class Foo {}",
                "src/main/resources/application.yml", "x: 1"));
        assertThat(counts.testFiles()).isZero();
        assertThat(counts.coveredLines()).isZero();
    }

    @Test
    @DisplayName("null/empty inputs return zero counts without exception")
    void nullSafe() {
        assertThat(PrAnnotationsBuilder.countTests(null).testFiles()).isZero();
        assertThat(PrAnnotationsBuilder.countTests(Map.of()).testFiles()).isZero();
    }
}
