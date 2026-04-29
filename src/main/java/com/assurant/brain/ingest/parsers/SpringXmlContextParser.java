package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.QueueNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
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
public class SpringXmlContextParser implements ArtifactParser {

    private static final Pattern XML_FILENAME = Pattern.compile(
            "(?i)^(application|service|batch|remote|dao|listener|integration|kafka|rabbit|jms)[^/]*context.*\\.xml$"
                    + "|^.+-context\\.xml$"
                    + "|^applicationContext.*\\.xml$"
                    + "|^batchContext.*\\.xml$");

    private static final List<String> SCAN_DIRECTORIES = List.of(
            "src/main/resources",
            "src/main/resources/config/spring",
            "src/main/resources/spring",
            "src/main/resources/META-INF/spring",
            "src/main/webapp/WEB-INF");

    @Override
    public String name() {
        return "SpringXmlContextParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return !findContextXmlFiles(context.projectPath()).isEmpty();
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, QueueNode> queues = new LinkedHashMap<>();
        int beanCount = 0;

        for (Path file : findContextXmlFiles(context.projectPath())) {
            try {
                Document doc = parseXml(file);
                if (doc == null) continue;
                beanCount += countBeans(doc);
                collectQueueListeners(doc, context, queues);
            } catch (Exception e) {
                log.debug("SpringXmlContextParser skipped {}: {}", file, e.getMessage());
            }
        }

        attachQueues(context.projectNode(), queues.values());
        log.info("SpringXmlContextParser scanned context XMLs for project={}: beans={}, queues={}",
                context.projectId(), beanCount, queues.size());
        return ParseResult.of(Map.of(
                "beans", beanCount,
                "queues", queues.size()));
    }

    private List<Path> findContextXmlFiles(Path projectPath) {
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
                    .filter(p -> XML_FILENAME.matcher(p.getFileName().toString()).matches())
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

    private int countBeans(Document doc) {
        return doc.getElementsByTagNameNS("*", "bean").getLength();
    }

    private void collectQueueListeners(Document doc, IngestionContext context,
                                        Map<String, QueueNode> queues) {
        addQueuesFromElements(doc, "rabbit", "listener-container", "queue-names", "RABBITMQ", context, queues);
        addQueuesFromElements(doc, "rabbit", "listener", "queue-names", "RABBITMQ", context, queues);
        addQueuesFromElements(doc, "jms", "listener", "destination", "JMS", context, queues);
        addQueuesFromElements(doc, "kafka", "listener-container", "topics", "KAFKA", context, queues);
    }

    private void addQueuesFromElements(Document doc, String namespacePrefix, String localName,
                                        String attribute, String queueType,
                                        IngestionContext context, Map<String, QueueNode> queues) {
        NodeList nodes = doc.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (!(node instanceof Element element)) continue;
            String prefix = element.getPrefix();
            if (prefix != null && !prefix.equalsIgnoreCase(namespacePrefix)) continue;
            String value = element.getAttribute(attribute);
            if (StringUtils.isBlank(value)) continue;
            for (String name : value.split(",")) {
                String trimmed = name.trim();
                if (trimmed.isEmpty()) continue;
                String id = context.projectId() + ":queue:" + queueType.toLowerCase() + ":" + trimmed;
                queues.computeIfAbsent(id, k -> {
                    QueueNode queue = new QueueNode();
                    queue.setId(id);
                    queue.setQueueType(queueType);
                    queue.setName(trimmed);
                    queue.setSource("SPRING_XML");
                    return queue;
                });
            }
        }
    }

    private void attachQueues(ProjectNode projectNode, Iterable<QueueNode> queues) {
        Set<String> existing = new HashSet<>();
        projectNode.getConsumedQueues().forEach(q -> existing.add(q.getId()));
        projectNode.getPublishedQueues().forEach(q -> existing.add(q.getId()));
        for (QueueNode queue : queues) {
            if (existing.add(queue.getId())) {
                projectNode.getConsumedQueues().add(queue);
            }
        }
    }
}
