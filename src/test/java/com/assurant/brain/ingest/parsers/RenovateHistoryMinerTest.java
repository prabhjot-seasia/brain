package com.assurant.brain.ingest.parsers;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.IngestDocumentFactory;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.RepoKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@DisplayName("RenovateHistoryMiner")
class RenovateHistoryMinerTest {

    private IngestDocumentFactory documentFactory;

    @BeforeEach
    void setup() {
        documentFactory = mock(IngestDocumentFactory.class);
    }

    private static BrainProperties propsWithToken(String token) {
        var gh = new BrainProperties.GitHub(token, "https://api.github.com", 3);
        return new BrainProperties(null, null, null, null, gh, null, null, null, null, null, null, null, null, null, null, null, null, null, new BrainProperties.Docs("mmdc", 60, 0, 0, 5), null);
    }

    private IngestionContext ctx(Path projectPath, String repoUrl) {
        ProjectNode pn = new ProjectNode();
        pn.setId("ce-imei");
        return new IngestionContext("ce-imei", projectPath, repoUrl, pn,
                RepoKind.APPLICATION, new ArrayList<>());
    }

    @Test
    @DisplayName("name() returns RenovateHistoryMiner")
    void hasName() {
        var miner = new RenovateHistoryMiner(propsWithToken("ghp_real"), documentFactory);
        assertThat(miner.name()).isEqualTo("RenovateHistoryMiner");
    }

    @Test
    @DisplayName("supports() false when token missing or not-configured")
    void rejectsMissingToken(@TempDir Path projectPath) {
        var ctx = ctx(projectPath, "https://github.com/foo/bar");
        assertThat(new RenovateHistoryMiner(propsWithToken(null), documentFactory).supports(ctx)).isFalse();
        assertThat(new RenovateHistoryMiner(propsWithToken("not-configured"), documentFactory).supports(ctx)).isFalse();
        assertThat(new RenovateHistoryMiner(propsWithToken(""), documentFactory).supports(ctx)).isFalse();
    }

    @Test
    @DisplayName("supports() false for blank or non-GitHub URL")
    void rejectsBadUrl(@TempDir Path projectPath) {
        var miner = new RenovateHistoryMiner(propsWithToken("ghp_real"), documentFactory);
        assertThat(miner.supports(ctx(projectPath, ""))).isFalse();
        assertThat(miner.supports(ctx(projectPath, null))).isFalse();
        assertThat(miner.supports(ctx(projectPath, "https://gitlab.com/foo/bar"))).isFalse();
    }

    @Test
    @DisplayName("supports() true for valid GitHub URL with configured token")
    void acceptsValidConfig(@TempDir Path projectPath) {
        var miner = new RenovateHistoryMiner(propsWithToken("ghp_real"), documentFactory);
        assertThat(miner.supports(ctx(projectPath, "https://github.com/foo/bar.git"))).isTrue();
        assertThat(miner.supports(ctx(projectPath, "git@github.com:foo/bar.git"))).isTrue();
    }

    @Test
    @DisplayName("parse() returns empty when URL is unparseable")
    void parseEmptyOnBadUrl(@TempDir Path projectPath) {
        var miner = new RenovateHistoryMiner(propsWithToken("ghp_real"), documentFactory);
        var result = miner.parse(ctx(projectPath, "garbage"));
        assertThat(result.stats()).isEmpty();
        assertThat(result.detectedGaps()).isEmpty();
    }
}
