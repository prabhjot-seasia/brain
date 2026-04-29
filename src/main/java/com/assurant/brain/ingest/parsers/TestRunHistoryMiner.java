package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.TestRunNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Log4j2
@Component
public class TestRunHistoryMiner implements ArtifactParser {

    private static final List<String> JUNIT_DIRECTORIES = List.of(
            "build/test-results",
            "target/surefire-reports",
            "target/failsafe-reports",
            "test-results");

    @Override
    public String name() {
        return "TestRunHistoryMiner";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return JUNIT_DIRECTORIES.stream()
                .anyMatch(dir -> Files.isDirectory(context.projectPath().resolve(dir)));
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, AggregatedRun> runsByFqn = new LinkedHashMap<>();

        for (String dir : JUNIT_DIRECTORIES) {
            Path root = context.projectPath().resolve(dir);
            if (!Files.isDirectory(root)) continue;
            walk(context.projectPath(), root, runsByFqn);
        }

        if (runsByFqn.isEmpty()) {
            return ParseResult.of(Map.of("testRuns", 0));
        }

        List<TestRunNode> nodes = runsByFqn.values().stream()
                .map(agg -> agg.toNode(context.projectId()))
                .toList();
        attachTestRuns(context.projectNode(), nodes);
        log.info("TestRunHistoryMiner ingested {} test-FQN aggregates for project={}",
                nodes.size(), context.projectId());
        return ParseResult.of(Map.of("testRuns", nodes.size()));
    }

    private void walk(Path projectPath, Path root, Map<String, AggregatedRun> runsByFqn) {
        try (Stream<Path> stream = IngestPathFilter.walkUnderRoot(projectPath, root)) {
            List<Path> xmls = stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return (name.startsWith("test") || name.endsWith("-junit.xml"))
                                && name.endsWith(".xml");
                    })
                    .filter(p -> IngestPathFilter.isParseSizeWithinLimit(p, IngestPathFilter.MAX_PARSE_BYTES))
                    .toList();
            for (Path xml : xmls) {
                processFile(xml, runsByFqn);
            }
        } catch (IOException e) {
            log.warn("TestRunHistoryMiner failed to walk {}: {}", root, e.getMessage());
        }
    }

    private void processFile(Path file, Map<String, AggregatedRun> runsByFqn) {
        try {
            Document doc = parseXml(file);
            if (doc == null) return;
            NodeList testcases = doc.getElementsByTagName("testcase");
            for (int i = 0; i < testcases.getLength(); i++) {
                if (!(testcases.item(i) instanceof Element element)) continue;
                String classname = element.getAttribute("classname");
                String testName = element.getAttribute("name");
                if (classname.isEmpty() || testName.isEmpty()) continue;
                String fqn = classname + "." + testName;
                AggregatedRun agg = runsByFqn.computeIfAbsent(fqn, AggregatedRun::new);
                agg.totalRuns++;
                if (hasFailureChild(element)) {
                    agg.failCount++;
                } else {
                    agg.passCount++;
                }
            }
        } catch (IOException | ParserConfigurationException | SAXException e) {
            log.debug("TestRunHistoryMiner skipped {}: {}", file, e.getMessage());
        }
    }

    private boolean hasFailureChild(Element testcase) {
        return testcase.getElementsByTagName("failure").getLength() > 0
                || testcase.getElementsByTagName("error").getLength() > 0;
    }

    private Document parseXml(Path file) throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        try {
            return factory.newDocumentBuilder().parse(file.toFile());
        } catch (SAXException e) {
            log.warn("TestRunHistoryMiner XML parse rejected for {}: {}", file, e.getMessage());
            return null;
        }
    }

    private void attachTestRuns(ProjectNode projectNode, List<TestRunNode> nodes) {
        Set<String> existing = new HashSet<>();
        projectNode.getTestRuns().forEach(t -> existing.add(t.getId()));
        for (TestRunNode node : nodes) {
            if (existing.add(node.getId())) {
                projectNode.getTestRuns().add(node);
            }
        }
    }

    private static final class AggregatedRun {
        final String fqn;
        int totalRuns;
        int passCount;
        int failCount;

        AggregatedRun(String fqn) {
            this.fqn = fqn;
        }

        TestRunNode toNode(String projectId) {
            TestRunNode node = new TestRunNode();
            node.setId(projectId + ":testRun:" + fqn);
            node.setProjectId(projectId);
            node.setTestFqn(fqn);
            node.setWorkflowName("LOCAL_BUILD");
            node.setTotalRuns(totalRuns);
            node.setPassCount(passCount);
            node.setFailCount(failCount);
            node.setFlakinessScore(totalRuns == 0 ? 0.0 : (double) failCount / totalRuns);
            node.setLastObservedAt(OffsetDateTime.now().toString());
            return node;
        }
    }
}
