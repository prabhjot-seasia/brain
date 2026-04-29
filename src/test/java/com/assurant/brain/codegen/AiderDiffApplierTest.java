package com.assurant.brain.codegen;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AiderDiffApplier")
class AiderDiffApplierTest {

    private final AiderDiffApplier applier = new AiderDiffApplier();

    @Test
    @DisplayName("parses a single SEARCH/REPLACE block with path header")
    void parsesSingleBlock() {
        String output = """
                src/Foo.java
                <<<<<<< SEARCH
                int x = 1;
                =======
                int x = 2;
                >>>>>>> REPLACE
                """;

        List<AiderDiffApplier.Block> blocks = applier.parse(output);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).path()).isEqualTo("src/Foo.java");
        assertThat(blocks.get(0).search()).isEqualTo("int x = 1;");
        assertThat(blocks.get(0).replace()).isEqualTo("int x = 2;");
    }

    @Test
    @DisplayName("apply replaces matched content verbatim and returns no errors")
    void applyReplacesContent() {
        Map<String, String> files = Map.of("src/Foo.java", "class Foo {\n    int x = 1;\n}\n");
        List<AiderDiffApplier.Block> blocks = List.of(
                new AiderDiffApplier.Block("src/Foo.java", "int x = 1;", "int x = 2;"));

        AiderDiffApplier.ApplyResult result = applier.apply(files, blocks);

        assertThat(result.ok()).isTrue();
        assertThat(result.updatedFiles().get("src/Foo.java")).contains("int x = 2;");
        assertThat(result.updatedFiles().get("src/Foo.java")).doesNotContain("int x = 1;");
    }

    @Test
    @DisplayName("apply errors when SEARCH does not match verbatim")
    void applyErrorsOnMismatch() {
        Map<String, String> files = Map.of("src/Foo.java", "class Foo {\n    int y = 9;\n}\n");
        List<AiderDiffApplier.Block> blocks = List.of(
                new AiderDiffApplier.Block("src/Foo.java", "int x = 1;", "int x = 2;"));

        AiderDiffApplier.ApplyResult result = applier.apply(files, blocks);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("does not match verbatim"));
    }

    @Test
    @DisplayName("empty SEARCH creates a new file")
    void emptySearchCreatesFile() {
        Map<String, String> files = Map.of("existing.txt", "x");
        List<AiderDiffApplier.Block> blocks = List.of(
                new AiderDiffApplier.Block("new/Bar.java", "", "public class Bar {}\n"));

        AiderDiffApplier.ApplyResult result = applier.apply(files, blocks);

        assertThat(result.ok()).isTrue();
        assertThat(result.updatedFiles().get("new/Bar.java")).isEqualTo("public class Bar {}\n");
    }

    @Test
    @DisplayName("apply errors when target file is unknown")
    void applyErrorsOnUnknownFile() {
        Map<String, String> files = Map.of();
        List<AiderDiffApplier.Block> blocks = List.of(
                new AiderDiffApplier.Block("src/Missing.java", "old", "new"));

        AiderDiffApplier.ApplyResult result = applier.apply(files, blocks);

        assertThat(result.ok()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.contains("no existing file"));
    }

    @Test
    @DisplayName("parses multiple sequential blocks")
    void parsesMultipleBlocks() {
        String output = """
                a.txt
                <<<<<<< SEARCH
                A
                =======
                A1
                >>>>>>> REPLACE

                b.txt
                <<<<<<< SEARCH
                B
                =======
                B1
                >>>>>>> REPLACE
                """;

        List<AiderDiffApplier.Block> blocks = applier.parse(output);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).path()).isEqualTo("a.txt");
        assertThat(blocks.get(1).path()).isEqualTo("b.txt");
    }
}
