package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.BatchJobNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Log4j2
@Component
public class SpringBatchXmlParser implements ArtifactParser {

    private static final Pattern BATCH_FILENAME = Pattern.compile(
            "(?i)^(batch[-_].+|.+[-_]batch).*context.*\\.xml$|^batchContext.*\\.xml$|^batch-context.*\\.xml$");

    private static final List<String> SCAN_DIRECTORIES = List.of(
            "src/main/resources",
            "src/main/resources/spring",
            "src/main/resources/spring/batch",
            "src/main/resources/config/spring",
            "src/main/resources/META-INF/spring");

    @Override
    public String name() {
        return "SpringBatchXmlParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return !findBatchXmlFiles(context.projectPath()).isEmpty();
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, BatchJobNode> jobs = new LinkedHashMap<>();
        for (Path file : findBatchXmlFiles(context.projectPath())) {
            try {
                Document doc = parseXml(file);
                if (doc == null) continue;
                collectJobs(doc, file, context, jobs);
            } catch (Exception e) {
                log.debug("SpringBatchXmlParser skipped {}: {}", file, e.getMessage());
            }
        }
        attachJobs(context.projectNode(), jobs.values());
        log.info("SpringBatchXmlParser ingested {} batch jobs for project={}",
                jobs.size(), context.projectId());
        return ParseResult.of(Map.of("batchJobs", jobs.size()));
    }

    private List<Path> findBatchXmlFiles(Path projectPath) {
        java.util.LinkedHashSet<Path> unique = new java.util.LinkedHashSet<>();
        for (String dir : SCAN_DIRECTORIES) {
            Path resolved = projectPath.resolve(dir);
            if (!Files.isDirectory(resolved)) continue;
            scanForXmls(resolved).forEach(unique::add);
        }
        return new java.util.ArrayList<>(unique);
    }

    private Stream<Path> scanForXmls(Path dir) {
        try (Stream<Path> stream = Files.walk(dir, 5)) {
            return stream.filter(p -> !IngestPathFilter.isSkippable(p))
                    .filter(Files::isRegularFile)
                    .filter(p -> BATCH_FILENAME.matcher(p.getFileName().toString()).matches())
                    .toList()
                    .stream();
        } catch (IOException e) {
            return Stream.empty();
        }
    }

    private Document parseXml(Path file) throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
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
            return null;
        }
    }

    private void collectJobs(Document doc, Path file, IngestionContext context,
                              Map<String, BatchJobNode> jobs) {
        NodeList nodes = doc.getElementsByTagNameNS("*", "job");
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) continue;
            String prefix = element.getPrefix();
            if (prefix != null && !prefix.equalsIgnoreCase("batch")) continue;
            String jobName = StringUtils.firstNonBlank(
                    element.getAttribute("id"), element.getAttribute("name"));
            if (StringUtils.isBlank(jobName)) continue;

            String relativePath = context.projectPath().relativize(file).toString();
            String id = context.projectId() + ":batchJob:" + jobName;
            jobs.computeIfAbsent(id, k -> {
                BatchJobNode job = new BatchJobNode();
                job.setId(id);
                job.setProjectId(context.projectId());
                job.setJobName(jobName);
                job.setFramework("SPRING_BATCH");
                job.setTrigger("XML_DEFINED");
                job.setSourceFile(relativePath);
                return job;
            });
        }
    }

    private void attachJobs(ProjectNode projectNode, Iterable<BatchJobNode> jobs) {
        Set<String> existing = new HashSet<>();
        projectNode.getBatchJobs().forEach(j -> existing.add(j.getId()));
        for (BatchJobNode job : jobs) {
            if (existing.add(job.getId())) {
                projectNode.getBatchJobs().add(job);
            }
        }
    }
}
