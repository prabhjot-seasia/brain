package com.assurant.brain.ingest.parsers;

import com.assurant.brain.graph.node.ProjectNode;
import com.assurant.brain.graph.node.SlowQueryNode;
import com.assurant.brain.ingest.ArtifactParser;
import com.assurant.brain.ingest.IngestPathFilter;
import com.assurant.brain.ingest.IngestionContext;
import com.assurant.brain.ingest.ParseResult;
import lombok.extern.log4j.Log4j2;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Log4j2
@Component
public class PgStatStatementsParser implements ArtifactParser {

    private static final List<String> CANDIDATE_PATHS = List.of(
            "ops/pg_stat_statements.csv",
            "ops/slow_queries.csv",
            "docs/ops/pg_stat_statements.csv",
            "pg_stat_statements.csv");

    private static final int MAX_QUERIES_PER_FILE = 200;

    @Override
    public String name() {
        return "PgStatStatementsParser";
    }

    @Override
    public boolean supports(IngestionContext context) {
        return resolve(context.projectPath()) != null;
    }

    @Override
    public ParseResult parse(IngestionContext context) {
        Path csv = resolve(context.projectPath());
        if (csv == null) return ParseResult.empty();

        Map<String, SlowQueryNode> queriesById = new LinkedHashMap<>();
        try {
            byte[] bytes = IngestPathFilter.readAllBytesIfWithinLimit(csv, IngestPathFilter.MAX_PARSE_BYTES);
            String content = new String(bytes, StandardCharsets.UTF_8);
            String[] lines = content.split("\\R");
            if (lines.length < 2) return ParseResult.empty();

            String[] header = splitCsv(lines[0]);
            int sqlIdx = indexOf(header, "query");
            int callsIdx = indexOf(header, "calls");
            int meanIdx = firstIndex(header, "mean_exec_time", "mean_time", "mean_time_ms");
            int totalIdx = firstIndex(header, "total_exec_time", "total_time", "total_time_ms");
            int p99Idx = firstIndex(header, "stddev_exec_time", "stddev_time", "p99");

            for (int i = 1; i < lines.length && queriesById.size() < MAX_QUERIES_PER_FILE; i++) {
                String[] cols = splitCsv(lines[i]);
                if (cols.length <= sqlIdx || sqlIdx < 0) continue;
                String sql = cols[sqlIdx].trim();
                if (StringUtils.isBlank(sql)) continue;

                SlowQueryNode node = new SlowQueryNode();
                String hash = sha256(sql);
                node.setId(context.projectId() + ":slowQuery:" + hash);
                node.setProjectId(context.projectId());
                node.setQueryHash(hash);
                node.setNormalizedSql(sql.length() > 1500 ? sql.substring(0, 1500) : sql);
                node.setCalls(parseLongAt(cols, callsIdx));
                node.setMeanTimeMs(parseDoubleAt(cols, meanIdx));
                node.setP99TimeMs(parseDoubleAt(cols, p99Idx));
                node.setTotalTimeMs(parseDoubleAt(cols, totalIdx));
                node.setCapturedAt(OffsetDateTime.now().toString());
                queriesById.putIfAbsent(node.getId(), node);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("PgStatStatementsParser failed at {}: {}", csv, e.getMessage());
            return ParseResult.empty();
        }

        attachSlowQueries(context.projectNode(), queriesById.values());
        log.info("PgStatStatementsParser ingested {} slow queries for project={}",
                queriesById.size(), context.projectId());
        return ParseResult.of(Map.of("slowQueries", queriesById.size()));
    }

    private Path resolve(Path projectPath) {
        for (String relative : CANDIDATE_PATHS) {
            Path candidate = projectPath.resolve(relative);
            if (Files.exists(candidate) && Files.isRegularFile(candidate)) return candidate;
        }
        return null;
    }

    private String[] splitCsv(String line) {
        return line.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)");
    }

    private int indexOf(String[] header, String name) {
        for (int i = 0; i < header.length; i++) {
            if (header[i].trim().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }

    private int firstIndex(String[] header, String... names) {
        for (String name : names) {
            int idx = indexOf(header, name);
            if (idx >= 0) return idx;
        }
        return -1;
    }

    private long parseLongAt(String[] cols, int idx) {
        if (idx < 0 || idx >= cols.length) return 0L;
        try {
            return Long.parseLong(cols[idx].trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private double parseDoubleAt(String[] cols, int idx) {
        if (idx < 0 || idx >= cols.length) return 0.0;
        try {
            return Double.parseDouble(cols[idx].trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private void attachSlowQueries(ProjectNode projectNode, Iterable<SlowQueryNode> queries) {
        Set<String> existing = new HashSet<>();
        projectNode.getSlowQueries().forEach(q -> existing.add(q.getId()));
        for (SlowQueryNode query : queries) {
            if (existing.add(query.getId())) {
                projectNode.getSlowQueries().add(query);
            }
        }
    }
}
