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

@DisplayName("TestRunHistoryMiner")
class TestRunHistoryMinerTest {

    private final TestRunHistoryMiner parser = new TestRunHistoryMiner();

    @Test
    @DisplayName("aggregates JUnit XML pass/fail counts per test FQN with flakiness score")
    void aggregatesJUnitResults(@TempDir Path projectRoot) throws IOException {
        Path resultsDir = projectRoot.resolve("build/test-results");
        Files.createDirectories(resultsDir);
        Files.writeString(resultsDir.resolve("TEST-com.example.OrderServiceTest-run1.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="OrderServiceTest" tests="2" failures="0">
                  <testcase classname="com.example.OrderServiceTest" name="happyPath" time="0.001"/>
                  <testcase classname="com.example.OrderServiceTest" name="flakyEdge" time="0.001"/>
                </testsuite>
                """);
        Files.writeString(resultsDir.resolve("TEST-com.example.OrderServiceTest-run2.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="OrderServiceTest" tests="2" failures="1">
                  <testcase classname="com.example.OrderServiceTest" name="happyPath" time="0.001"/>
                  <testcase classname="com.example.OrderServiceTest" name="flakyEdge" time="0.001">
                    <failure message="boom"/>
                  </testcase>
                </testsuite>
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());

        assertThat(parser.supports(context)).isTrue();
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getTestRuns()).hasSize(2);
        var flaky = projectNode.getTestRuns().stream()
                .filter(t -> t.getTestFqn().endsWith(".flakyEdge"))
                .findFirst().orElseThrow();
        assertThat(flaky.getTotalRuns()).isEqualTo(2);
        assertThat(flaky.getFailCount()).isEqualTo(1);
        assertThat(flaky.getFlakinessScore()).isEqualTo(0.5);
        assertThat(result.stats().get("testRuns")).isEqualTo(2);
    }

    @Test
    @DisplayName("supports() false when no test-results directory exists")
    void supportsFalseWithoutResults(@TempDir Path projectRoot) {
        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        assertThat(parser.supports(context)).isFalse();
    }

    @Test
    @DisplayName("malformed JUnit XML is skipped; multi-file aggregation continues")
    void malformedXmlSkippedNotFatal(@TempDir Path projectRoot) throws IOException {
        Path resultsDir = projectRoot.resolve("build/test-results");
        Files.createDirectories(resultsDir);
        Files.writeString(resultsDir.resolve("TEST-broken.xml"),
                "<?xml version=\"1.0\"?><testsuite><testcase name=\"x\" classname=\"X\"</broken>");
        Files.writeString(resultsDir.resolve("TEST-good.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="GoodTest" tests="1" failures="0">
                  <testcase classname="com.example.GoodTest" name="passes"/>
                </testsuite>
                """);

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getTestRuns()).hasSize(1);
        assertThat(projectNode.getTestRuns().get(0).getTestFqn()).isEqualTo("com.example.GoodTest.passes");
        assertThat(result.stats().get("testRuns")).isEqualTo(1);
    }

    @Test
    @DisplayName("rejects XML with DOCTYPE declaration (XXE protection)")
    void rejectsDoctypeXxe(@TempDir Path projectRoot) throws IOException {
        Path resultsDir = projectRoot.resolve("build/test-results");
        Files.createDirectories(resultsDir);
        Files.writeString(resultsDir.resolve("TEST-xxe.xml"),
                "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
                        + "<testsuite><testcase classname=\"&x;\" name=\"x\"/></testsuite>");

        ProjectNode projectNode = new ProjectNode();
        IngestionContext context = new IngestionContext(
                "proj-1", projectRoot, null, projectNode, RepoKind.APPLICATION, new ArrayList<>());
        ParseResult result = parser.parse(context);

        assertThat(projectNode.getTestRuns()).isEmpty();
        assertThat(result.stats().get("testRuns")).isEqualTo(0);
    }
}
