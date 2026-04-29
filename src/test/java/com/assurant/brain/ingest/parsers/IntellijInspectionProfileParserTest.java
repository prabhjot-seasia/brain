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

@DisplayName("IntellijInspectionProfileParser")
class IntellijInspectionProfileParserTest {

    private final IntellijInspectionProfileParser parser = new IntellijInspectionProfileParser();

    @Test
    @DisplayName("emits a ConventionNode per enabled inspection_tool")
    void parsesEnabledInspections(@TempDir Path projectRoot) throws IOException {
        Path profilesDir = projectRoot.resolve(".idea/inspectionProfiles");
        Files.createDirectories(profilesDir);
        Files.writeString(profilesDir.resolve("Project_Default.xml"), """
                <component name="InspectionProjectProfileManager">
                  <profile version="1.0">
                    <inspection_tool class="UnusedImport" enabled="true" level="WARNING" enabled_by_default="true"/>
                    <inspection_tool class="MagicNumber" enabled="true" level="ERROR" enabled_by_default="true"/>
                    <inspection_tool class="DanglingJavadoc" enabled="false" level="WARNING" enabled_by_default="false"/>
                  </profile>
                </component>
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getConventions())
                .anyMatch(c -> c.getCategory().equals("INTELLIJ_INSPECTION")
                        && c.getRule().contains("UnusedImport") && c.getRule().contains("WARNING"));
        assertThat(projectNode.getConventions())
                .anyMatch(c -> c.getRule().contains("MagicNumber") && c.getRule().contains("ERROR"));
        assertThat(projectNode.getConventions())
                .noneMatch(c -> c.getRule().contains("DanglingJavadoc"));
        assertThat(result.stats().get("intellijInspections")).isEqualTo(2);
    }

    @Test
    @DisplayName("supports() false when no .idea/inspectionProfiles directory")
    void supportsFalseWithoutDir(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }

    @Test
    @DisplayName("malformed XML degrades safely without exceptions")
    void malformedXmlGraceful(@TempDir Path projectRoot) throws IOException {
        Path profilesDir = projectRoot.resolve(".idea/inspectionProfiles");
        Files.createDirectories(profilesDir);
        Files.writeString(profilesDir.resolve("Broken.xml"),
                "<component><inspection_tool class=\"X\" enabled=\"true\" level=\"WARNING\"");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        ParseResult result = parser.parse(context);

        assertThat(result.stats().get("intellijInspectionProfiles")).isEqualTo(1);
    }
}
