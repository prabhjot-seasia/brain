package com.assurant.brain.guardrail.rails;

import com.assurant.brain.enums.RailPhase;
import com.assurant.brain.enums.RailType;
import com.assurant.brain.guardrail.Rail;
import com.assurant.brain.guardrail.RailContext;
import com.assurant.brain.guardrail.RailResult;
import com.assurant.brain.util.LlmJsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Log4j2
@Component
@RequiredArgsConstructor
public class OutputSchemaRail implements Rail {

    public static final String METADATA_SCHEMA_KEY = "outputSchemaResource";
    private static final int PRIORITY = 10;

    private final ObjectMapper objectMapper;
    private JsonSchemaFactory schemaFactory;
    private final Map<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();

    @PostConstruct
    void init() {
        schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    }

    @Override
    public RailResult apply(RailContext context) {
        String output = context.rawOutput();
        if (output == null || output.isBlank()) return RailResult.pass(type());

        Object schemaMeta = context.metadata() == null ? null : context.metadata().get(METADATA_SCHEMA_KEY);
        if (!(schemaMeta instanceof String schemaResource) || schemaResource.isBlank()) {
            return RailResult.pass(type());
        }

        try {
            JsonSchema schema = loadSchema(schemaResource);
            String stripped = LlmJsonParser.stripFences(output);
            JsonNode json = objectMapper.readTree(stripped);
            Set<ValidationMessage> errors = schema.validate(json);
            if (errors.isEmpty()) return RailResult.pass(type());

            List<String> messages = errors.stream().map(ValidationMessage::getMessage).toList();
            return RailResult.block(type(), messages);
        } catch (IOException e) {
            log.error("Failed to validate LLM output against schema {}: {}", schemaResource, e.getMessage());
            return RailResult.block(type(), List.of("Schema validation error: " + e.getMessage()));
        }
    }

    private JsonSchema loadSchema(String resource) throws IOException {
        JsonSchema cached = schemaCache.get(resource);
        if (cached != null) return cached;

        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            JsonSchema schema = schemaFactory.getSchema(in);
            schemaCache.put(resource, schema);
            return schema;
        }
    }

    @Override
    public RailPhase phase() { return RailPhase.POST_LLM; }

    @Override
    public int priority() { return PRIORITY; }

    @Override
    public RailType type() { return RailType.SCHEMA; }
}
