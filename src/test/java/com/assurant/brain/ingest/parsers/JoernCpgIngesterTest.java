package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.RepoKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JoernCpgIngester")
class JoernCpgIngesterTest {

    private final JoernCpgIngester ingester = new JoernCpgIngester();

    private IngestionContext ctx(Path projectPath) {
        ProjectNode pn = new ProjectNode();
        pn.setId("ce-imei");
        return new IngestionContext("ce-imei", projectPath, "https://example.com/foo/bar", pn,
                RepoKind.APPLICATION, new ArrayList<>());
    }

    @Test
    @DisplayName("name() returns JoernCpgIngester")
    void hasName() {
        assertThat(ingester.name()).isEqualTo("JoernCpgIngester");
    }

    @Test
    @DisplayName("supports() returns false when no CPG directories exist")
    void rejectsNoCpgDirs(@TempDir Path projectPath) {
        assertThat(ingester.supports(ctx(projectPath))).isFalse();
    }

    @Test
    @DisplayName("supports() returns true when ops/cpg directory exists")
    void detectsOpsCpgDir(@TempDir Path projectPath) throws Exception {
        Files.createDirectories(projectPath.resolve("ops/cpg"));
        assertThat(ingester.supports(ctx(projectPath))).isTrue();
    }

    @Test
    @DisplayName("supports() returns true when ops/security/cpg exists")
    void detectsOpsSecurityCpgDir(@TempDir Path projectPath) throws Exception {
        Files.createDirectories(projectPath.resolve("ops/security/cpg"));
        assertThat(ingester.supports(ctx(projectPath))).isTrue();
    }

    @Test
    @DisplayName("supports() returns true when docs/ops/cpg exists")
    void detectsDocsOpsCpgDir(@TempDir Path projectPath) throws Exception {
        Files.createDirectories(projectPath.resolve("docs/ops/cpg"));
        assertThat(ingester.supports(ctx(projectPath))).isTrue();
    }

    @Test
    @DisplayName("parse() with no CPG artifacts emits zero-count stats")
    void parseEmptyDir(@TempDir Path projectPath) throws Exception {
        Files.createDirectories(projectPath.resolve("ops/cpg"));
        var result = ingester.parse(ctx(projectPath));
        assertThat(result.stats()).containsEntry("cpgArtifacts", 0);
    }

    @Test
    @DisplayName("parse() picks up .cpg.bin / .atom / .cpg.json artifacts")
    void parsePicksUpKnownExtensions(@TempDir Path projectPath) throws Exception {
        Path cpgDir = projectPath.resolve("ops/cpg");
        Files.createDirectories(cpgDir);
        Files.writeString(cpgDir.resolve("alpha.cpg.bin"), "binary");
        Files.writeString(cpgDir.resolve("beta.atom"), "atom");
        Files.writeString(cpgDir.resolve("gamma.cpg.json"), "{}");
        Files.writeString(cpgDir.resolve("ignored.txt"), "noise");

        var result = ingester.parse(ctx(projectPath));
        assertThat(result.stats()).containsEntry("cpgArtifacts", 3);
    }
}
