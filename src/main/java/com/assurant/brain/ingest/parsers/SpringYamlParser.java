package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.QueueNode;
import com.assurant.brain.graph.node.ServiceNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

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
public class SpringYamlParser implements ArtifactParser {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    private static final Pattern URL_VALUE_PATTERN =
            Pattern.compile("(?i)^(https?://|bolt://|grpc://|tcp://|amqps?://|kafka://)\\S+");
    private static final Pattern URL_KEY_SUFFIX =
            Pattern.compile("(?i)\\.(url|endpoint|host|base-url|baseurl|uri)$");
    private static final Pattern APPS_PREFIX = Pattern.compile("^apps\\.([^.]+)$");
    private static final Pattern HOSTNAME_VALUE = Pattern.compile("(?i)^[a-z0-9.-]+\\.[a-z]{2,}");

    private static final Set<String> INFRA_KEY_PREFIXES = Set.of(
            "spring.datasource",
            "spring.jpa",
            "spring.cloud.config",
            "spring.neo4j",
            "spring.data.redis",
            "spring.ai.ollama",
            "spring.ai.bedrock",
            "spring.ai.anthropic",
            "spring.ai.openai",
            "spring.ai.vectorstore",
            "spring.liquibase",
            "spring.servlet",
            "spring.lifecycle",
            "spring.autoconfigure",
            "spring.application",
            "spring.config",
            "spring.profiles",
            "logging",
            "management",
            "server",
            "brain.embed",
            "brain.llm",
            "brain.rag",
            "brain.cache",
            "brain.security",
            "brain.guardrails",
            "brain.intake",
            "brain.cost",
            "brain.codegen",
            "brain.clarifier",
            "brain.learning",
            "brain.autodev",
            "brain.ci",
            "brain.chunk"
    );

    private static final Pattern SQS_KEY = Pattern.compile("(?i)(^|\\.)sqs(\\.|$)|queue-url$|queue\\.url$|sqs.*queue");
    private static final Pattern KAFKA_KEY = Pattern.compile("(?i)(^|\\.)kafka(\\.|$)|topics?$|topic[-.]name$");
    private static final Pattern RABBIT_KEY = Pattern.compile("(?i)(^|\\.)rabbitmq(\\.|$)|routing[-.]key$|exchange$");

    private static final Pattern YAML_FILENAME = Pattern.compile(
            "(?i)^(application|bootstrap)(?:[-_].+)?\\.(yml|yaml)$");

    @Override
    public String name() {
        return "SpringYamlParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        try (Stream<Path> stream = IngestPathFilter.safeWalk(context.projectPath(), context.projectPath(), 6)) {
            return stream.filter(Files::isRegularFile)
                    .anyMatch(p -> YAML_FILENAME.matcher(p.getFileName().toString()).matches());
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, ServiceNode> servicesById = new LinkedHashMap<>();
        Map<String, QueueNode> queuesById = new LinkedHashMap<>();
        int filesScanned = 0;

        try (Stream<Path> stream = IngestPathFilter.safeWalk(context.projectPath(), context.projectPath(), 6)) {
            List<Path> yamlFiles = stream.filter(Files::isRegularFile)
                    .filter(p -> YAML_FILENAME.matcher(p.getFileName().toString()).matches())
                    .filter(p -> IngestPathFilter.isParseSizeWithinLimit(p, IngestPathFilter.MAX_PARSE_BYTES))
                    .toList();
            for (Path file : yamlFiles) {
                filesScanned++;
                processFile(file, context, servicesById, queuesById);
            }
        } catch (IOException e) {
            log.warn("SpringYamlParser failed to walk project path for project={}: {}",
                    context.projectId(), e.getMessage());
        }

        attachServices(context.projectNode(), servicesById.values());
        attachConsumedQueues(context.projectNode(), queuesById.values());

        log.info("SpringYamlParser parsed {} YAML files for project={}: services={}, queues={}",
                filesScanned, context.projectId(), servicesById.size(), queuesById.size());
        return ParseResult.of(Map.of(
                "yamlFiles", filesScanned,
                "services", servicesById.size(),
                "queues", queuesById.size()));
    }

    private void processFile(Path file, IngestionContext context,
                             Map<String, ServiceNode> services,
                             Map<String, QueueNode> queues) {
        try (JsonParser parser = YAML_MAPPER.getFactory().createParser(Files.newInputStream(file))) {
            MappingIterator<Object> docs = YAML_MAPPER.readValues(parser, new TypeReference<>() {});
            while (docs.hasNext()) {
                Object doc = docs.next();
                if (!(doc instanceof Map<?, ?> map)) continue;
                Map<String, Object> flat = new LinkedHashMap<>();
                flatten("", map, flat);
                interpretFlatMap(flat, context, services, queues);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("SpringYamlParser skipped {}: {}", file, e.getMessage());
        }
    }

    private static final int MAX_FLATTEN_DEPTH = 32;
    private static final int MAX_VALUE_LENGTH = 4096;

    private void flatten(String prefix, Map<?, ?> source, Map<String, Object> sink) {
        flatten(prefix, source, sink, 0);
    }

    private void flatten(String prefix, Map<?, ?> source, Map<String, Object> sink, int depth) {
        if (depth > MAX_FLATTEN_DEPTH) return;
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            String key = prefix.isEmpty() ? String.valueOf(entry.getKey()) : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> nested) {
                flatten(key, nested, sink, depth + 1);
            } else {
                sink.put(key, value);
            }
        }
    }

    private void interpretFlatMap(Map<String, Object> flat, IngestionContext context,
                                   Map<String, ServiceNode> services,
                                   Map<String, QueueNode> queues) {
        for (Map.Entry<String, Object> entry : flat.entrySet()) {
            String key = entry.getKey();
            Object rawValue = entry.getValue();
            String value = rawValue == null ? "" : String.valueOf(rawValue);
            if (StringUtils.isBlank(value)) continue;
            if (value.length() > MAX_VALUE_LENGTH) continue;

            if (matchesAppsPrefix(key, value, context, services)) continue;
            if (matchesQueueKey(key, value, context, queues)) continue;
            if (isInfraKey(key)) continue;
            if (matchesUrlKey(key, value, context, services)) continue;
        }
    }

    private boolean matchesAppsPrefix(String key, String value, IngestionContext context,
                                      Map<String, ServiceNode> services) {
        var matcher = APPS_PREFIX.matcher(key);
        if (!matcher.matches()) return false;
        String name = matcher.group(1);
        addService(name, value, "APPS_YAML", context, services);
        return true;
    }

    private boolean matchesUrlKey(String key, String value, IngestionContext context,
                                  Map<String, ServiceNode> services) {
        boolean keySuggestsUrl = URL_KEY_SUFFIX.matcher(key).find();
        boolean valueLooksLikeUrl = URL_VALUE_PATTERN.matcher(value).find()
                || (HOSTNAME_VALUE.matcher(value).find() && !value.contains(" "));
        if (!keySuggestsUrl || !valueLooksLikeUrl) return false;
        String name = inferServiceNameFromKey(key);
        if (StringUtils.isBlank(name)) return false;
        addService(name, value, "URL_KEY", context, services);
        return true;
    }

    private String inferServiceNameFromKey(String key) {
        String trimmed = key.replaceFirst("(?i)\\.(url|endpoint|host|base-url|baseurl|uri)$", "");
        if (trimmed.isEmpty()) return "";
        int lastDot = trimmed.lastIndexOf('.');
        return lastDot >= 0 ? trimmed.substring(lastDot + 1) : trimmed;
    }

    private boolean matchesQueueKey(String key, String value, IngestionContext context,
                                    Map<String, QueueNode> queues) {
        if (SQS_KEY.matcher(key).find()) {
            addQueue("SQS", queueName(key, value), value, context, queues);
            return true;
        }
        if (KAFKA_KEY.matcher(key).find()) {
            String name = queueName(key, value);
            if (StringUtils.isBlank(name)) return false;
            addQueue("KAFKA", name, value, context, queues);
            return true;
        }
        if (RABBIT_KEY.matcher(key).find()) {
            String name = queueName(key, value);
            if (StringUtils.isBlank(name)) return false;
            addQueue("RABBITMQ", name, value, context, queues);
            return true;
        }
        return false;
    }

    private String queueName(String key, String value) {
        if (key.endsWith("name") || key.endsWith("topic") || key.endsWith("topics")
                || key.endsWith("routing-key") || key.endsWith("exchange") || key.endsWith("queue")) {
            return value;
        }
        if (value.startsWith("arn:aws:sqs:") || value.startsWith("arn:aws:sns:")) {
            int colon = value.lastIndexOf(':');
            return colon > 0 && colon + 1 < value.length() ? value.substring(colon + 1) : value;
        }
        if (value.contains("amazonaws.com/")) {
            int slash = value.lastIndexOf('/');
            return slash > 0 && slash + 1 < value.length() ? value.substring(slash + 1) : value;
        }
        return "";
    }

    private boolean isInfraKey(String key) {
        return INFRA_KEY_PREFIXES.stream().anyMatch(prefix ->
                key.equals(prefix) || key.startsWith(prefix + "."));
    }

    private void addService(String rawName, String value, String source,
                            IngestionContext context, Map<String, ServiceNode> services) {
        String name = StringUtils.lowerCase(rawName).replaceAll("[^a-z0-9-]", "");
        if (name.isEmpty()) return;
        String id = context.projectId() + ":service:" + name;
        services.computeIfAbsent(id, k -> {
            ServiceNode node = new ServiceNode();
            node.setId(id);
            node.setName(name);
            node.setBaseUrlTemplate(value);
            node.setSource(source);
            return node;
        });
    }

    private void addQueue(String type, String rawName, String value, IngestionContext context,
                          Map<String, QueueNode> queues) {
        String name = StringUtils.defaultIfBlank(StringUtils.trimToEmpty(rawName), value);
        if (name.isBlank()) return;
        String id = context.projectId() + ":queue:" + type.toLowerCase() + ":" + name;
        queues.computeIfAbsent(id, k -> {
            QueueNode node = new QueueNode();
            node.setId(id);
            node.setQueueType(type);
            node.setName(name);
            node.setSource("YAML");
            if (value.startsWith("arn:")) {
                node.setArn(value);
            }
            return node;
        });
    }

    private void attachServices(ProjectNode projectNode, Iterable<ServiceNode> services) {
        Set<String> existing = new HashSet<>();
        projectNode.getCalledServices().forEach(s -> existing.add(s.getId()));
        for (ServiceNode service : services) {
            if (existing.add(service.getId())) {
                projectNode.getCalledServices().add(service);
            }
        }
    }

    private void attachConsumedQueues(ProjectNode projectNode, Iterable<QueueNode> queues) {
        Set<String> existingConsumed = new HashSet<>();
        projectNode.getConsumedQueues().forEach(q -> existingConsumed.add(q.getId()));
        Set<String> existingPublished = new HashSet<>();
        projectNode.getPublishedQueues().forEach(q -> existingPublished.add(q.getId()));
        for (QueueNode queue : queues) {
            if (!existingConsumed.contains(queue.getId()) && !existingPublished.contains(queue.getId())) {
                projectNode.getConsumedQueues().add(queue);
                existingConsumed.add(queue.getId());
            }
        }
    }
}
