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

@DisplayName("SpringPropertiesParser")
class SpringPropertiesParserTest {

    private final SpringPropertiesParser parser = new SpringPropertiesParser();

    @Test
    @DisplayName("parses properties file into ConfigKeyNodes ignoring infra keys")
    void parsesProperties(@TempDir Path projectRoot) throws IOException {
        Path resources = projectRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("flipswap.properties"), """
                aws.s3.bucket=hyla-flipswap-bucket
                cdn.url=https://cdn.flipswap.com
                shipping.url=https://shipping.flipswap.com
                spring.datasource.url=jdbc:postgresql://localhost:5432/test
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getConfigKeys()).hasSize(3);
        assertThat(projectNode.getConfigKeys()).noneMatch(k -> k.getKey().startsWith("spring.datasource"));
        assertThat(projectNode.getConfigKeys()).anyMatch(k -> k.getKey().equals("cdn.url"));
        assertThat(result.stats().get("configKeys")).isEqualTo(3);
    }

    @Test
    @DisplayName("infers TenantNode from filename suffix and links via OVERRIDDEN_FOR")
    void inferTenantFromSuffix(@TempDir Path projectRoot) throws IOException {
        Path resources = projectRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("applicationConfig_vzw.properties"), """
                carrier.name=Verizon
                carrier.feed.url=https://feed.vzw.com
                """);
        Files.writeString(resources.resolve("applicationConfig_att.properties"), """
                carrier.name=AT&T
                carrier.feed.url=https://feed.att.com
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getTenants()).hasSize(2);
        assertThat(projectNode.getTenants()).anyMatch(t -> t.getName().equals("vzw"));
        assertThat(projectNode.getTenants()).anyMatch(t -> t.getName().equals("att"));
        assertThat(projectNode.getConfigKeys())
                .anyMatch(k -> k.getKey().equals("carrier.name") && k.getTenantOverride() != null
                        && k.getTenantOverride().getName().equals("vzw"));
        assertThat(result.stats().get("tenants")).isEqualTo(2);
    }

    @Test
    @DisplayName("ignores localization bundles like messages_en_US.properties")
    void ignoresLocalizationBundles(@TempDir Path projectRoot) throws IOException {
        Path resources = projectRoot.resolve("src/main/resources");
        Files.createDirectories(resources);
        Files.writeString(resources.resolve("messages_en_US.properties"),
                "ui.title=Hello\nui.subtitle=World");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        parser.parse(context);

        assertThat(projectNode.getConfigKeys()).isEmpty();
    }
}
