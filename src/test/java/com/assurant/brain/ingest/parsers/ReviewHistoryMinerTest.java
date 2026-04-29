package com.assurant.brain.ingest.parsers;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.RepoKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ReviewHistoryMiner")
class ReviewHistoryMinerTest {

    @Test
    @DisplayName("supports() false when GitHub token is not configured")
    void supportsFalseWithoutToken(@TempDir Path projectRoot) {
        ReviewHistoryMiner miner = new ReviewHistoryMiner(propertiesWithToken("not-configured"));
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, "https://github.com/owner/repo", projectNode,
                RepoKind.APPLICATION, new ArrayList<>());
        assertThat(miner.supports(context)).isFalse();
    }

    @Test
    @DisplayName("supports() false when repoUrl is missing")
    void supportsFalseWithoutRepoUrl(@TempDir Path projectRoot) {
        ReviewHistoryMiner miner = new ReviewHistoryMiner(propertiesWithToken("ghp_real_token_xyz"));
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(miner.supports(context)).isFalse();
    }

    @Test
    @DisplayName("supports() false when repoUrl is unparseable")
    void supportsFalseWhenRepoUrlIsUnparseable(@TempDir Path projectRoot) {
        ReviewHistoryMiner miner = new ReviewHistoryMiner(propertiesWithToken("ghp_real_token_xyz"));
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, "not-a-github-url", projectNode,
                RepoKind.APPLICATION, new ArrayList<>());
        assertThat(miner.supports(context)).isFalse();
    }

    @Test
    @DisplayName("supports() true when token is configured AND repoUrl parses to owner/repo")
    void supportsTrueWhenConfigured(@TempDir Path projectRoot) {
        ReviewHistoryMiner miner = new ReviewHistoryMiner(propertiesWithToken("ghp_real_token_xyz"));
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, "https://github.com/owner/repo.git", projectNode,
                RepoKind.APPLICATION, new ArrayList<>());
        assertThat(miner.supports(context)).isTrue();
    }

    @Test
    @DisplayName("name() returns ReviewHistoryMiner")
    void hasName() {
        ReviewHistoryMiner miner = new ReviewHistoryMiner(propertiesWithToken("ghp"));
        assertThat(miner.name()).isEqualTo("ReviewHistoryMiner");
    }

    @Test
    @DisplayName("supports() false when GitHub config is null")
    void supportsFalseWithNullGithubConfig(@TempDir Path projectRoot) {
        var props = new BrainProperties(null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
        ReviewHistoryMiner miner = new ReviewHistoryMiner(props);
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, "https://github.com/owner/repo", projectNode,
                RepoKind.APPLICATION, new ArrayList<>());
        assertThat(miner.supports(context)).isFalse();
    }

    @Test
    @DisplayName("parse() returns empty when repoUrl is unparseable")
    void parseEmptyOnBadUrl(@TempDir Path projectRoot) {
        ReviewHistoryMiner miner = new ReviewHistoryMiner(propertiesWithToken("ghp_real"));
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, "not a url", projectNode,
                RepoKind.APPLICATION, new ArrayList<>());
        var result = miner.parse(context);
        assertThat(result.stats()).isEmpty();
    }

    private static BrainProperties propertiesWithToken(String token) {
        BrainProperties.GitHub github = new BrainProperties.GitHub(token, "https://api.github.com", 3);
        return new BrainProperties(null, null, null, null, github, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null);
    }
}
