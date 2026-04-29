package com.assurant.brain.ingest.parsers;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.enums.ChunkType;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestDocumentFactory;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@Component
@RequiredArgsConstructor
public class RenovateHistoryMiner implements ArtifactParser {

    private static final String NOT_CONFIGURED = "not-configured";
    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final int LOOKBACK_DAYS = 90;
    private static final int MAX_PAGES = 5;
    private static final int PER_PAGE = 100;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final Pattern OWNER_REPO_PATTERN = Pattern.compile(
            "github\\.com[:/]+([^/]+)/([^/.]+)(?:\\.git)?/?$");
    private static final Set<String> BOT_LOGINS = Set.of(
            "renovate[bot]", "renovate-bot", "dependabot[bot]", "dependabot-preview[bot]");

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final BrainProperties brainProperties;
    private final IngestDocumentFactory documentFactory;

    @Override
    public String name() {
        return "RenovateHistoryMiner";
    }

    @Override
    public boolean supports(IngestionContext context) {
        if (StringUtils.isBlank(context.repoUrl())) return false;
        if (extractOwnerAndRepo(context.repoUrl()) == null) return false;
        return brainProperties.github() != null
                && StringUtils.isNotBlank(brainProperties.github().token())
                && !NOT_CONFIGURED.equals(brainProperties.github().token());
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        OwnerRepo ownerRepo = extractOwnerAndRepo(context.repoUrl());
        if (ownerRepo == null) {
            return ParseResult.empty();
        }

        OffsetDateTime since = OffsetDateTime.now().minusDays(LOOKBACK_DAYS);
        BotPullRequestStats stats;
        try {
            stats = countBotPullRequests(ownerRepo, since);
        } catch (RuntimeException e) {
            log.warn("RenovateHistoryMiner skipped for {}/{}: {}",
                    ownerRepo.owner(), ownerRepo.repo(), e.getMessage());
            return ParseResult.empty();
        }

        if (stats.totalPrs == 0) {
            log.info("RenovateHistoryMiner found no bot PRs for {}/{} in last {} days",
                    ownerRepo.owner(), ownerRepo.repo(), LOOKBACK_DAYS);
            return ParseResult.of(Map.of("botPrs", 0));
        }

        emitSummaryChunk(context, ownerRepo, stats);
        log.info("RenovateHistoryMiner ingested bot history for {}/{}: total={}, merged={}, byAuthor={}",
                ownerRepo.owner(), ownerRepo.repo(), stats.totalPrs, stats.mergedPrs, stats.byAuthor);

        return ParseResult.of(Map.of(
                "botPrs", stats.totalPrs,
                "mergedBotPrs", stats.mergedPrs,
                "byAuthor", stats.byAuthor));
    }

    private BotPullRequestStats countBotPullRequests(OwnerRepo ownerRepo, OffsetDateTime since) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);

        RestClient client = RestClient.builder()
                .baseUrl(GITHUB_API_BASE)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + brainProperties.github().token())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        BotPullRequestStats stats = new BotPullRequestStats();
        for (int page = 1; page <= MAX_PAGES; page++) {
            String body = client.get()
                    .uri("/repos/{owner}/{repo}/pulls?state=closed&per_page={perPage}&page={page}&sort=updated&direction=desc",
                            ownerRepo.owner(), ownerRepo.repo(), PER_PAGE, page)
                    .retrieve()
                    .body(String.class);

            JsonNode array;
            try {
                array = JSON_MAPPER.readTree(body);
            } catch (Exception e) {
                log.debug("Failed to parse GitHub PR list page {}: {}", page, e.getMessage());
                break;
            }
            if (!array.isArray() || array.isEmpty()) break;

            boolean foundOlderThanWindow = false;
            for (JsonNode pr : array) {
                String updatedAt = pr.path("updated_at").asText("");
                OffsetDateTime updated = parseDate(updatedAt);
                if (updated != null && updated.isBefore(since)) {
                    foundOlderThanWindow = true;
                    continue;
                }
                String author = pr.path("user").path("login").asText("");
                if (!BOT_LOGINS.contains(author.toLowerCase())) continue;

                stats.totalPrs++;
                if (!pr.path("merged_at").isNull() && StringUtils.isNotBlank(pr.path("merged_at").asText(""))) {
                    stats.mergedPrs++;
                }
                stats.byAuthor.merge(author, 1, Integer::sum);
            }
            if (foundOlderThanWindow) break;
        }
        return stats;
    }

    private void emitSummaryChunk(IngestionContext context, OwnerRepo ownerRepo, BotPullRequestStats stats) {
        StringBuilder summary = new StringBuilder();
        summary.append("Renovate / Dependabot Activity Summary\n\n")
                .append("Repository: ").append(ownerRepo.owner()).append("/").append(ownerRepo.repo()).append("\n")
                .append("Lookback window: ").append(LOOKBACK_DAYS).append(" days\n")
                .append("Total bot pull requests: ").append(stats.totalPrs).append("\n")
                .append("Merged bot pull requests: ").append(stats.mergedPrs).append("\n");
        if (!stats.byAuthor.isEmpty()) {
            summary.append("\nBy author:\n");
            stats.byAuthor.forEach((author, count) ->
                    summary.append(" - ").append(author).append(": ").append(count).append("\n"));
        }
        context.documents().add(documentFactory.buildDocChunk(
                context.projectId(),
                "renovate-history-summary",
                ChunkType.README,
                "renovate-history",
                summary.toString()));
    }

    private OwnerRepo extractOwnerAndRepo(String repoUrl) {
        if (StringUtils.isBlank(repoUrl)) return null;
        Matcher m = OWNER_REPO_PATTERN.matcher(repoUrl.trim());
        if (!m.find()) return null;
        return new OwnerRepo(m.group(1), m.group(2));
    }

    private OffsetDateTime parseDate(String value) {
        if (StringUtils.isBlank(value)) return null;
        try {
            return OffsetDateTime.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private record OwnerRepo(String owner, String repo) {}

    private static class BotPullRequestStats {
        int totalPrs;
        int mergedPrs;
        Map<String, Integer> byAuthor = new HashMap<>();
    }
}
