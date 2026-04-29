package com.assurant.brain.ingest.parsers;

import com.assurant.brain.config.properties.BrainProperties;
import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.ReviewPatternNode;
import com.assurant.brain.ingest.ArtifactParser;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Log4j2
@Component
@RequiredArgsConstructor
public class ReviewHistoryMiner implements ArtifactParser {

    private static final String NOT_CONFIGURED = "not-configured";
    private static final String GITHUB_API_BASE = "https://api.github.com";
    private static final int MAX_PR_PAGES = 5;
    private static final int PER_PAGE = 100;
    private static final int OCCURRENCE_THRESHOLD = 3;
    private static final int MAX_PATTERNS_PER_PROJECT = 25;
    private static final int MIN_PHRASE_LENGTH = 12;
    private static final int MAX_PHRASE_LENGTH = 200;
    private static final int MAX_INCOMING_BODY_BYTES = 4096;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_EXEMPLAR_URLS = 3;

    private static final Pattern OWNER_REPO_PATTERN = Pattern.compile(
            "github\\.com[:/]+([^/]+)/([^/.]+)(?:\\.git)?/?$");
    private static final Pattern STOPWORD_PATTERN = Pattern.compile("(?i)^(thanks|lgtm|nit|done|wfm|fixed|ok|yep|sounds good)\\W*$");
    private static final Set<String> BOT_LOGINS = Set.of(
            "renovate[bot]", "renovate-bot", "dependabot[bot]", "dependabot-preview[bot]",
            "github-actions[bot]");

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    private final BrainProperties brainProperties;

    @Override
    public String name() {
        return "ReviewHistoryMiner";
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
        if (ownerRepo == null) return ParseResult.empty();

        List<ReviewComment> comments;
        try {
            comments = fetchRecentReviewComments(ownerRepo);
        } catch (RuntimeException e) {
            log.warn("ReviewHistoryMiner skipped for {}/{}: {}",
                    ownerRepo.owner(), ownerRepo.repo(), e.getMessage());
            return ParseResult.empty();
        }
        if (comments.isEmpty()) {
            return ParseResult.of(Map.of("comments", 0, "patterns", 0));
        }

        Map<String, ReviewCluster> clusters = clusterByPhrase(comments);
        List<ReviewPatternNode> patterns = new ArrayList<>();
        clusters.values().stream()
                .filter(c -> c.occurrences >= OCCURRENCE_THRESHOLD)
                .sorted(Comparator.comparingInt(ReviewCluster::occurrences).reversed())
                .limit(MAX_PATTERNS_PER_PROJECT)
                .forEach(c -> patterns.add(buildPattern(context.projectId(), c)));

        attachPatterns(context.projectNode(), patterns);
        log.info("ReviewHistoryMiner ingested {} review comments → {} candidate patterns for project={}",
                comments.size(), patterns.size(), context.projectId());
        return ParseResult.of(Map.of(
                "comments", comments.size(),
                "patterns", patterns.size()));
    }

    private List<ReviewComment> fetchRecentReviewComments(OwnerRepo ownerRepo) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);

        RestClient client = RestClient.builder()
                .baseUrl(GITHUB_API_BASE)
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + brainProperties.github().token())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();

        List<ReviewComment> all = new ArrayList<>();
        for (int page = 1; page <= MAX_PR_PAGES; page++) {
            String body = client.get()
                    .uri("/repos/{owner}/{repo}/pulls/comments?per_page={perPage}&page={page}&sort=created&direction=desc",
                            ownerRepo.owner(), ownerRepo.repo(), PER_PAGE, page)
                    .retrieve()
                    .body(String.class);
            JsonNode array;
            try {
                array = JSON_MAPPER.readTree(body);
            } catch (Exception e) {
                log.debug("Failed to parse review-comments page {}: {}", page, e.getMessage());
                break;
            }
            if (!array.isArray() || array.isEmpty()) break;
            for (JsonNode comment : array) {
                String author = comment.path("user").path("login").asText("");
                if (BOT_LOGINS.contains(author.toLowerCase())) continue;
                String text = comment.path("body").asText("");
                if (text.isBlank()) continue;
                all.add(new ReviewComment(
                        author,
                        text,
                        comment.path("path").asText(""),
                        comment.path("html_url").asText(""),
                        comment.path("pull_request_url").asText("")));
            }
        }
        return all;
    }

    private Map<String, ReviewCluster> clusterByPhrase(List<ReviewComment> comments) {
        Map<String, ReviewCluster> clusters = new HashMap<>();
        for (ReviewComment c : comments) {
            String phrase = normalizePhrase(c.body());
            if (StringUtils.isBlank(phrase)) continue;
            if (phrase.length() < MIN_PHRASE_LENGTH || phrase.length() > MAX_PHRASE_LENGTH) continue;
            if (STOPWORD_PATTERN.matcher(phrase).matches()) continue;
            ReviewCluster cluster = clusters.computeIfAbsent(phrase, ReviewCluster::new);
            cluster.occurrences++;
            if (StringUtils.isNotBlank(c.author())) cluster.reviewers.add(c.author());
            if (StringUtils.isNotBlank(c.htmlUrl()) && cluster.exemplarPrUrls.size() < MAX_EXEMPLAR_URLS) {
                cluster.exemplarPrUrls.add(c.htmlUrl());
            }
        }
        return clusters;
    }

    private String normalizePhrase(String body) {
        String capped = body.length() > MAX_INCOMING_BODY_BYTES
                ? body.substring(0, MAX_INCOMING_BODY_BYTES)
                : body;
        String firstLine = capped.split("\\R", 2)[0].trim();
        firstLine = firstLine.replaceAll("`[^`]*`", "X").trim();
        firstLine = firstLine.replaceAll("\\s+", " ");
        firstLine = firstLine.replaceAll("(?i)@\\w+", "@user");
        firstLine = firstLine.replaceAll("\\S+@\\S+\\.\\S+", "[redacted-email]");
        if (firstLine.length() > MAX_PHRASE_LENGTH) {
            return firstLine.substring(0, MAX_PHRASE_LENGTH);
        }
        return firstLine;
    }

    private ReviewPatternNode buildPattern(String projectId, ReviewCluster cluster) {
        ReviewPatternNode node = new ReviewPatternNode();
        node.setId(projectId + ":reviewPattern:" + sha256Prefix(cluster.phrase));
        node.setProjectId(projectId);
        node.setPhrase(cluster.phrase);
        node.setOccurrences(cluster.occurrences);
        node.setReviewers(cluster.reviewers.stream().map(this::hashReviewer).toList());
        node.setExemplarPrUrls(new ArrayList<>(cluster.exemplarPrUrls));
        node.setStatus("CANDIDATE");
        return node;
    }

    private String hashReviewer(String login) {
        return "reviewer:" + sha256Prefix(login);
    }

    private static String sha256Prefix(String input) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(16);
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void attachPatterns(ProjectNode projectNode, List<ReviewPatternNode> patterns) {
        Set<String> existing = new java.util.HashSet<>();
        projectNode.getReviewPatterns().forEach(p -> existing.add(p.getId()));
        for (ReviewPatternNode pattern : patterns) {
            if (existing.add(pattern.getId())) {
                projectNode.getReviewPatterns().add(pattern);
            }
        }
    }

    private OwnerRepo extractOwnerAndRepo(String repoUrl) {
        if (StringUtils.isBlank(repoUrl)) return null;
        Matcher m = OWNER_REPO_PATTERN.matcher(repoUrl.trim());
        if (!m.find()) return null;
        return new OwnerRepo(m.group(1), m.group(2));
    }

    private record OwnerRepo(String owner, String repo) {}

    private record ReviewComment(String author, String body, String path, String htmlUrl, String pullRequestUrl) {}

    private static final class ReviewCluster {
        final String phrase;
        int occurrences;
        final Set<String> reviewers = new LinkedHashSet<>();
        final List<String> exemplarPrUrls = new ArrayList<>();

        ReviewCluster(String phrase) {
            this.phrase = phrase;
        }

        int occurrences() {
            return occurrences;
        }
    }
}
