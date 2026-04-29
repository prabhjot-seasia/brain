package com.assurant.brain.rest.v1.monitor;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.dao.TokenUsageRecordRepository;
import com.assurant.brain.domain.TokenUsageRecord;
import com.assurant.brain.enums.LlmOperation;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.service.ClarifierService;
import com.assurant.brain.service.PlannerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("TokenUsageController Integration Tests")
class TokenUsageControllerIT extends BrainApplicationTests {

    @MockitoBean ClarifierService clarifierService;
    @MockitoBean PlannerService plannerService;
    @MockitoBean ProjectNodeRepository projectNodeRepository;
    @MockitoBean ConventionNodeRepository conventionNodeRepository;
    @MockitoBean VectorStore vectorStore;
    @MockitoBean com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;

    @Autowired TokenUsageRecordRepository tokenUsageRecordRepository;

    @AfterEach
    void cleanTokenRecords() {
        tokenUsageRecordRepository.deleteAll();
    }

    @Nested
    @DisplayName("GET /api/v1/monitor/token-usage")
    class TokenUsageSummaryEndpoint {

        @Test
        @DisplayName("200 OK — returns summary with zero counts when empty")
        void emptySummary() throws Exception {
            mvc.perform(get("/api/v1/monitor/token-usage").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalCalls").value(0))
                    .andExpect(jsonPath("$.cacheHits").value(0))
                    .andExpect(jsonPath("$.cacheHitRate").value(0.0))
                    .andExpect(jsonPath("$.totalInputTokens").value(0))
                    .andExpect(jsonPath("$.totalOutputTokens").value(0))
                    .andExpect(jsonPath("$.breakdown").isArray());
        }

        @Test
        @DisplayName("200 OK — aggregates token usage records correctly")
        void aggregatedSummary() throws Exception {
            seedRecord("PlannerService", LlmOperation.PLAN, 100, 50, false, 1.25);
            seedRecord("PlannerService", LlmOperation.PLAN, 200, 100, true, 0.0);

            mvc.perform(get("/api/v1/monitor/token-usage").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalCalls").value(2))
                    .andExpect(jsonPath("$.cacheHits").value(1))
                    .andExpect(jsonPath("$.totalInputTokens").value(300))
                    .andExpect(jsonPath("$.totalOutputTokens").value(150))
                    .andExpect(jsonPath("$.breakdown", hasSize(1)))
                    .andExpect(jsonPath("$.breakdown[0].serviceName").value("PlannerService"));
        }

        @Test
        @DisplayName("200 OK — hours param filters to recent records")
        void hoursParamFilters() throws Exception {
            mvc.perform(get("/api/v1/monitor/token-usage?hours=1").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalCalls").value(0));
        }
    }

    @Nested
    @DisplayName("GET /api/v1/monitor/token-usage/recent")
    class RecentEndpoint {

        @Test
        @DisplayName("200 OK — returns empty list when no records")
        void emptyRecent() throws Exception {
            mvc.perform(get("/api/v1/monitor/token-usage/recent").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray())
                    .andExpect(jsonPath("$", hasSize(0)));
        }

        @Test
        @DisplayName("200 OK — respects limit param")
        void limitParam() throws Exception {
            for (int i = 0; i < 5; i++) {
                seedRecord("Svc", LlmOperation.PLAN, 10, 5, false, 0.01);
            }

            mvc.perform(get("/api/v1/monitor/token-usage/recent?limit=3").accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(3)));
        }
    }

    private void seedRecord(String service, LlmOperation op, int input, int output,
                             boolean cached, double cost) {
        TokenUsageRecord r = new TokenUsageRecord();
        r.setServiceName(service);
        r.setOperation(op);
        r.setInputTokens(input);
        r.setOutputTokens(output);
        r.setCached(cached);
        r.setCostEstimate(cost);
        r.setLatencyMs(100L);
        r.setModelName("claude-sonnet");
        tokenUsageRecordRepository.save(r);
    }
}
