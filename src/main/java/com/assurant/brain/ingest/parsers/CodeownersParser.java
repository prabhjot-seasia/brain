package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.TeamNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Log4j2
@Component
public class CodeownersParser implements ArtifactParser {

    private static final List<String> CODEOWNERS_LOCATIONS = List.of(
            ".github/CODEOWNERS",
            "CODEOWNERS",
            "docs/CODEOWNERS"
    );

    @Override
    public String name() {
        return "CodeownersParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolveCodeownersPath(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path codeowners = resolveCodeownersPath(context.projectPath());
        if (codeowners == null) {
            return ParseResult.empty();
        }

        Map<String, TeamNode> teamsByName = new HashMap<>();
        int rules = 0;
        try {
            List<String> lines = Files.readAllLines(codeowners);
            for (String raw : lines) {
                String line = stripComments(raw).trim();
                if (line.isEmpty()) continue;
                String[] tokens = line.split("\\s+");
                if (tokens.length < 2) continue;
                String pathPattern = tokens[0];
                for (int i = 1; i < tokens.length; i++) {
                    String owner = tokens[i].trim();
                    if (owner.isEmpty()) continue;
                    if (!owner.startsWith("@") && !owner.contains("@")) continue;
                    TeamNode team = teamsByName.computeIfAbsent(owner, k -> buildTeam(context.projectId(), k));
                    if (!team.getOwnedPaths().contains(pathPattern)) {
                        team.getOwnedPaths().add(pathPattern);
                    }
                }
                rules++;
            }
        } catch (IOException e) {
            log.warn("Failed to read CODEOWNERS at {}: {}", codeowners, e.getMessage());
            return ParseResult.empty();
        }

        List<TeamNode> teams = new ArrayList<>(teamsByName.values());
        if (!teams.isEmpty()) {
            context.projectNode().getOwners().addAll(teams);
            log.info("CodeownersParser parsed {} rules → {} teams for project={}",
                    rules, teams.size(), context.projectId());
        }
        return ParseResult.of(Map.of(
                "teams", teams.size(),
                "rules", rules));
    }

    private Path resolveCodeownersPath(Path projectPath) {
        for (String relative : CODEOWNERS_LOCATIONS) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private TeamNode buildTeam(String projectId, String ownerHandle) {
        TeamNode team = new TeamNode();
        team.setId(projectId + ":" + ownerHandle);
        team.setName(ownerHandle);
        team.setSource("CODEOWNERS");
        return team;
    }

    private String stripComments(String line) {
        int hash = line.indexOf('#');
        return hash >= 0 ? line.substring(0, hash) : line;
    }
}
