package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.assurant.brain.ingest.RepoKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SbomParser")
class SbomParserTest {

    private final SbomParser parser = new SbomParser();

    @Test
    @DisplayName("parses CycloneDX SBOM into SbomNode + components with licenses")
    void parsesCycloneDxSbom(@TempDir Path projectRoot) throws IOException {
        String sbom = """
                {
                  "bomFormat": "CycloneDX",
                  "specVersion": "1.6",
                  "components": [
                    { "type": "library", "name": "spring-core", "version": "6.1.0",
                      "purl": "pkg:maven/org.springframework/spring-core@6.1.0",
                      "licenses": [{ "license": { "id": "Apache-2.0" } }] },
                    { "type": "library", "name": "lombok", "version": "1.18.30",
                      "purl": "pkg:maven/org.projectlombok/lombok@1.18.30",
                      "licenses": [{ "license": { "name": "MIT" } }] }
                  ]
                }
                """;
        Files.writeString(projectRoot.resolve("bom.json"), sbom);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getSboms()).hasSize(1);
        assertThat(projectNode.getSboms().get(0).getComponents()).hasSize(2);
        assertThat(projectNode.getSboms().get(0).getComponents().get(0).getLicenses())
                .containsExactly("Apache-2.0");
        assertThat(projectNode.getSboms().get(0).getComponents().get(1).getLicenses())
                .containsExactly("MIT");
        assertThat(result.stats().get("componentCount")).isEqualTo(2);
    }

    @Test
    @DisplayName("rejects non-CycloneDX SBOM gracefully")
    void rejectsNonCycloneDx(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("sbom.json"),
                "{\"bomFormat\":\"SPDX\",\"components\":[]}");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getSboms()).isEmpty();
        assertThat(result.stats()).isEmpty();
    }

    @Test
    @DisplayName("does not throw on malformed JSON; falls back gracefully")
    void doesNotThrowOnMalformedJson(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("bom.json"), "{ this is not valid json");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        ParseResult result = parser.parse(context);
        assertThat(projectNode.getSboms()).isEmpty();
        assertThat(result.stats()).isEmpty();
    }
}
