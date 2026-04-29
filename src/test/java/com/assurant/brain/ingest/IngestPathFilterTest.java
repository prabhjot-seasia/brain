package com.assurant.brain.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("IngestPathFilter")
class IngestPathFilterTest {

    @Test
    @DisplayName("isSkippable returns true for build/git/node_modules paths and macOS junk")
    void isSkippableMatchesKnownNoise() {
        assertThat(IngestPathFilter.isSkippable(Path.of("/x/.git/HEAD"))).isTrue();
        assertThat(IngestPathFilter.isSkippable(Path.of("/x/build/classes/Foo.class"))).isTrue();
        assertThat(IngestPathFilter.isSkippable(Path.of("/x/node_modules/lodash/index.js"))).isTrue();
        assertThat(IngestPathFilter.isSkippable(Path.of("/x/target/site/index.html"))).isTrue();
        assertThat(IngestPathFilter.isSkippable(Path.of("/x/.gradle/8.12/foo"))).isTrue();
        assertThat(IngestPathFilter.isSkippable(Path.of("/x/dist/main.js"))).isTrue();
        assertThat(IngestPathFilter.isSkippable(Path.of("/x/.DS_Store"))).isTrue();
        assertThat(IngestPathFilter.isSkippable(Path.of("/x/._abc.txt"))).isTrue();
    }

    @Test
    @DisplayName("isSkippable returns false for ordinary source paths")
    void isSkippableLeavesSourcePathsAlone() {
        assertThat(IngestPathFilter.isSkippable(Path.of("/x/src/main/java/Foo.java"))).isFalse();
        assertThat(IngestPathFilter.isSkippable(Path.of("/x/docs/adr/0001.md"))).isFalse();
        assertThat(IngestPathFilter.isSkippable(Path.of("/x/.github/workflows/ci.yml"))).isFalse();
    }

    @Test
    @DisplayName("readAllBytesIfWithinLimit returns content when under cap, throws when over")
    void readAllBytesEnforcesSizeCap(@TempDir Path tmp) throws IOException {
        Path small = tmp.resolve("small.txt");
        Files.writeString(small, "hello world");
        byte[] bytes = IngestPathFilter.readAllBytesIfWithinLimit(small, 1024);
        assertThat(new String(bytes)).isEqualTo("hello world");

        Path big = tmp.resolve("big.txt");
        Files.writeString(big, "X".repeat(2048));
        assertThatThrownBy(() -> IngestPathFilter.readAllBytesIfWithinLimit(big, 1024))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("exceeds size limit");
    }

    @Test
    @DisplayName("safeWalk yields filtered descendants and stays within project root")
    void safeWalkYieldsFilteredDescendants(@TempDir Path projectRoot) throws IOException {
        Files.createDirectories(projectRoot.resolve(".git"));
        Files.writeString(projectRoot.resolve(".git/HEAD"), "ref");
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.writeString(projectRoot.resolve("src/main/java/Foo.java"), "class Foo {}");

        try (var stream = IngestPathFilter.safeWalk(projectRoot, projectRoot, 5)) {
            assertThat(stream.filter(Files::isRegularFile))
                    .allSatisfy(p -> assertThat(p.toString()).doesNotContain(".git"));
        }
    }
}
