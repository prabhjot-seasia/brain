package com.assurant.brain.rest.v1.monitor;

import com.assurant.brain.dao.TokenUsageRecordRepository;
import com.assurant.brain.domain.TokenUsageRecord;
import com.assurant.brain.dto.response.TokenUsageSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/monitor")
@RequiredArgsConstructor
public class TokenUsageController {

    private final TokenUsageRecordRepository repository;

    @GetMapping("/token-usage")
    public ResponseEntity<TokenUsageSummary> getSummary(
            @RequestParam(defaultValue = "24") int hours) {
        OffsetDateTime since = OffsetDateTime.now().minusHours(hours);

        long totalCalls = repository.countTotalSince(since);
        long cacheHits = repository.countCacheHitsSince(since);
        double hitRate = totalCalls > 0 ? (double) cacheHits / totalCalls : 0.0;

        List<Object[]> aggregates = repository.aggregateSince(since);
        long totalInput = 0;
        long totalOutput = 0;
        double totalCost = 0;

        List<TokenUsageSummary.ServiceBreakdown> breakdown = new ArrayList<>();
        for (Object[] row : aggregates) {
            String service = (String) row[0];
            String operation = row[1].toString();
            long input = ((Number) row[2]).longValue();
            long output = ((Number) row[3]).longValue();
            long count = ((Number) row[4]).longValue();
            double cost = ((Number) row[5]).doubleValue();

            totalInput += input;
            totalOutput += output;
            totalCost += cost;

            breakdown.add(new TokenUsageSummary.ServiceBreakdown(service, operation, input, output, count, cost));
        }

        return ResponseEntity.ok(new TokenUsageSummary(
                totalCalls, cacheHits, hitRate, totalInput, totalOutput, totalCost, breakdown));
    }

    @GetMapping("/token-usage/recent")
    public ResponseEntity<List<TokenUsageRecord>> getRecent(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit)));
    }
}
