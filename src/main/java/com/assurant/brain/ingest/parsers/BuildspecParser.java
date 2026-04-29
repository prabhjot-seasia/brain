package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.BuildVariantNode;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Log4j2
@Component
public class BuildspecParser implements ArtifactParser {

    private static final Pattern BUILDSPEC_FILENAME = Pattern.compile(
            "(?i)^buildspec(?:[-_].+)?\\.(yml|yaml)$");

    private static final Pattern CARRIER_SUFFIX = Pattern.compile(
            "(?i)^buildspec[-_](att|vzw|google|telus|bby|cisco|tracfone|uscc|canada|walmart)[-_].*\\.(yml|yaml)$"
                    + "|^buildspec[-_](att|vzw|google|telus|bby|cisco|tracfone|uscc|canada|walmart)\\.(yml|yaml)$");

    private static final Pattern VERSION_SUFFIX = Pattern.compile(
            "(?i)^buildspec[-_]v?(\\d+).*\\.(yml|yaml)$");

    @Override
    public String name() {
        return "BuildspecParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return !findBuildspecs(context.projectPath()).isEmpty();
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Map<String, BuildVariantNode> variantsById = new LinkedHashMap<>();
        for (Path file : findBuildspecs(context.projectPath())) {
            BuildVariantNode variant = buildVariant(file, context);
            variantsById.put(variant.getId(), variant);
        }
        attachVariants(context.projectNode(), variantsById.values());
        log.info("BuildspecParser ingested {} build variants for project={}",
                variantsById.size(), context.projectId());
        return ParseResult.of(Map.of("buildVariants", variantsById.size()));
    }

    private List<Path> findBuildspecs(Path projectPath) {
        try (Stream<Path> stream = Files.walk(projectPath, 3)) {
            return stream.filter(p -> !IngestPathFilter.isSkippable(p))
                    .filter(Files::isRegularFile)
                    .filter(p -> BUILDSPEC_FILENAME.matcher(p.getFileName().toString()).matches())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private BuildVariantNode buildVariant(Path file, IngestionContext context) {
        String filename = file.getFileName().toString();
        String relativePath = context.projectPath().relativize(file).toString();

        BuildVariantNode variant = new BuildVariantNode();
        variant.setId(context.projectId() + ":buildVariant:" + filename);
        variant.setProjectId(context.projectId());
        variant.setName(filename);
        variant.setSourceFile(relativePath);

        Matcher carrierMatch = CARRIER_SUFFIX.matcher(filename);
        if (carrierMatch.matches()) {
            String carrier = carrierMatch.group(1) != null ? carrierMatch.group(1) : carrierMatch.group(3);
            variant.setTarget("CARRIER");
            variant.setTargetValue(carrier.toLowerCase());
            return variant;
        }

        Matcher versionMatch = VERSION_SUFFIX.matcher(filename);
        if (versionMatch.matches()) {
            variant.setTarget("VERSION");
            variant.setTargetValue("v" + versionMatch.group(1));
            return variant;
        }

        variant.setTarget("DEFAULT");
        variant.setTargetValue("default");
        return variant;
    }

    private void attachVariants(ProjectNode projectNode, Iterable<BuildVariantNode> variants) {
        Set<String> existing = new HashSet<>();
        projectNode.getBuildVariants().forEach(v -> existing.add(v.getId()));
        for (BuildVariantNode variant : variants) {
            if (existing.add(variant.getId())) {
                projectNode.getBuildVariants().add(variant);
            }
        }
    }
}
