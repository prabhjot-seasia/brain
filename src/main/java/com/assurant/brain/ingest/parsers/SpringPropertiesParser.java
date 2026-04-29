package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ConfigKeyNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.TenantNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Log4j2
@Component
public class SpringPropertiesParser implements ArtifactParser {

    private static final int MAX_KEYS_PER_FILE = 200;

    private static final Set<String> KNOWN_TENANTS = Set.of(
            "vzw", "att", "google", "telus", "uscc", "wind", "koodo", "walmart",
            "tracfone", "target", "nret", "sasktel", "bby", "cisco", "docomo",
            "canada", "tmobile", "totalwireless", "xfinitymobile");

    private static final Pattern TENANT_SUFFIX_FILENAME =
            Pattern.compile("(?i)^(.+?)[_-]([a-z]{2,15})\\.properties$");

    private static final Pattern LOCALIZATION_FILENAME =
            Pattern.compile("(?i)^messages(_[a-z]{2,3}([_-][a-z]{2,3})?)?\\.properties$");

    private static final Set<String> INFRA_KEY_PREFIXES = Set.of(
            "spring.datasource", "spring.jpa", "spring.cloud.config", "spring.neo4j",
            "spring.data.redis", "spring.ai", "spring.liquibase", "spring.servlet",
            "spring.application", "spring.config", "spring.profiles",
            "logging", "management", "server",
            "brain.embed", "brain.llm", "brain.rag", "brain.cache", "brain.security",
            "brain.guardrails", "brain.intake", "brain.cost", "brain.codegen",
            "brain.clarifier", "brain.learning", "brain.autodev", "brain.ci", "brain.chunk");

    @Override
    public String name() {
        return "SpringPropertiesParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        try (Stream<Path> stream = IngestPathFilter.safeWalk(context.projectPath(), context.projectPath(), 6)) {
            return stream.filter(Files::isRegularFile)
                    .anyMatch(p -> p.getFileName().toString().toLowerCase().endsWith(".properties"));
        } catch (IOException e) {
            return false;
        }
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, ConfigKeyNode> keysById = new LinkedHashMap<>();
        Map<String, TenantNode> tenantsByName = new LinkedHashMap<>();
        int filesParsed = 0;

        try (Stream<Path> stream = IngestPathFilter.safeWalk(context.projectPath(), context.projectPath(), 6)) {
            List<Path> files = stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".properties"))
                    .toList();
            for (Path file : files) {
                String filename = file.getFileName().toString();
                if (LOCALIZATION_FILENAME.matcher(filename).matches()) continue;
                filesParsed++;
                processFile(file, context, keysById, tenantsByName);
            }
        } catch (IOException e) {
            log.warn("SpringPropertiesParser failed to walk project path for project={}: {}",
                    context.projectId(), e.getMessage());
        }

        attachConfigKeys(context.projectNode(), keysById.values());
        attachTenants(context.projectNode(), tenantsByName.values());

        log.info("SpringPropertiesParser parsed {} files for project={}: configKeys={}, tenants={}",
                filesParsed, context.projectId(), keysById.size(), tenantsByName.size());
        return ParseResult.of(Map.of(
                "filesParsed", filesParsed,
                "configKeys", keysById.size(),
                "tenants", tenantsByName.size()));
    }

    private void processFile(Path file, IngestionContext context,
                             Map<String, ConfigKeyNode> keysById,
                             Map<String, TenantNode> tenantsByName) {
        String relativePath = context.projectPath().relativize(file).toString();
        String filename = file.getFileName().toString();
        TenantNode tenant = inferTenantFromFilename(context.projectId(), filename, tenantsByName);

        Properties props = new Properties();
        try {
            String content = Files.readString(file);
            props.load(new StringReader(content));
        } catch (IOException e) {
            log.debug("SpringPropertiesParser skipped {}: {}", file, e.getMessage());
            return;
        }

        int kept = 0;
        for (Map.Entry<Object, Object> entry : props.entrySet()) {
            if (kept >= MAX_KEYS_PER_FILE) {
                log.debug("SpringPropertiesParser truncated {} at {} keys", relativePath, MAX_KEYS_PER_FILE);
                break;
            }
            String key = String.valueOf(entry.getKey());
            String value = String.valueOf(entry.getValue());
            if (StringUtils.isBlank(key) || isInfraKey(key)) continue;

            String tenantSuffix = tenant != null ? ":" + tenant.getName() : "";
            String id = context.projectId() + ":configKey:" + key + tenantSuffix;
            ConfigKeyNode existing = keysById.get(id);
            if (existing != null) continue;

            ConfigKeyNode node = new ConfigKeyNode();
            node.setId(id);
            node.setProjectId(context.projectId());
            node.setKey(key);
            node.setValueTemplate(value);
            node.setSourceFile(relativePath);
            if (tenant != null) {
                node.setTenantOverride(tenant);
            }
            keysById.put(id, node);
            kept++;
        }
    }

    private TenantNode inferTenantFromFilename(String projectId, String filename,
                                                Map<String, TenantNode> tenantsByName) {
        Matcher m = TENANT_SUFFIX_FILENAME.matcher(filename);
        if (!m.matches()) return null;
        String candidate = m.group(2).toLowerCase();
        if (!KNOWN_TENANTS.contains(candidate)) return null;
        return tenantsByName.computeIfAbsent(candidate, name -> {
            TenantNode tenant = new TenantNode();
            tenant.setId(projectId + ":tenant:" + name);
            tenant.setName(name);
            tenant.setDisplayName(name.toUpperCase());
            tenant.setSource("PROPERTIES_SUFFIX");
            return tenant;
        });
    }

    private boolean isInfraKey(String key) {
        return INFRA_KEY_PREFIXES.stream().anyMatch(prefix ->
                key.equals(prefix) || key.startsWith(prefix + "."));
    }

    private void attachConfigKeys(ProjectNode projectNode, Iterable<ConfigKeyNode> keys) {
        Set<String> existing = new HashSet<>();
        projectNode.getConfigKeys().forEach(k -> existing.add(k.getId()));
        for (ConfigKeyNode key : keys) {
            if (existing.add(key.getId())) {
                projectNode.getConfigKeys().add(key);
            }
        }
    }

    private void attachTenants(ProjectNode projectNode, Iterable<TenantNode> tenants) {
        Set<String> existing = new HashSet<>();
        projectNode.getTenants().forEach(t -> existing.add(t.getId()));
        for (TenantNode tenant : tenants) {
            if (existing.add(tenant.getId())) {
                projectNode.getTenants().add(tenant);
            }
        }
    }
}
