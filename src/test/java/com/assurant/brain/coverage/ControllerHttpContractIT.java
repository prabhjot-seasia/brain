package com.assurant.brain.coverage;

import com.assurant.brain.BrainApplicationTests;
import com.assurant.brain.dao.PullRequestRecordRepository;
import com.assurant.brain.domain.PullRequestRecord;
import com.assurant.brain.enums.PrStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("HTTP-contract coverage — controllers without prior MockMvc tests")
class ControllerHttpContractIT extends BrainApplicationTests {

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ConventionNodeRepository conventionNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.ProjectNodeRepository projectNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    org.springframework.ai.vectorstore.VectorStore vectorStore;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.assurant.brain.incident.IncidentExplainerService incidentExplainerService;

    @Autowired private ObjectMapper objectMapper;
    @Autowired private PullRequestRecordRepository prRepository;
    @Autowired private com.assurant.brain.dao.AvengerReviewRepository avengerReviewRepo;
    @Autowired private com.assurant.brain.dao.LearningEventRepository learningEventRepo;
    @Autowired private com.assurant.brain.dao.CiRemediationAttemptRepository ciAttemptRepo;

    @AfterEach
    void cleanup() {
        avengerReviewRepo.deleteAll();
        ciAttemptRepo.deleteAll();
        prRepository.deleteAll();
        learningEventRepo.deleteAll();
    }

    // ─── IncidentExplainerController (truly no test) ──────────────────
    @Nested
    @DisplayName("IncidentExplainerController")
    class Incidents {
        @Test
        @DisplayName("POST /incidents/explain returns 200 with markdown")
        void explain() throws Exception {
            when(incidentExplainerService.explainIncident(eq("p1"), eq("Foo")))
                    .thenReturn("# Incident\n\nExplanation here");

            String body = objectMapper.writeValueAsString(Map.of("projectId", "p1", "classHint", "Foo"));
            mvc.perform(post("/api/v1/incidents/explain")
                            .contentType("application/json").content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.markdown").exists());
        }

        @Test
        @DisplayName("POST /incidents/explain validates required fields")
        void explainValidation() throws Exception {
            mvc.perform(post("/api/v1/incidents/explain")
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }

    // ─── HawkeyeAsffController ────────────────────────────────────────
    @Nested
    @DisplayName("HawkeyeAsffController")
    class Hawkeye {
        @Test
        @DisplayName("GET /avengers/hawkeye/findings.asff returns ASFF batch JSON")
        void findings() throws Exception {
            mvc.perform(get("/api/v1/avengers/hawkeye/findings.asff").param("projectId", "p1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.Findings").exists());
        }
    }

    // ─── OracleBudgetController ───────────────────────────────────────
    @Nested
    @DisplayName("OracleBudgetController")
    class Oracle {
        @Test
        @DisplayName("GET /avengers/oracle/budget/{projectId} returns 200 (empty when soft-mode)")
        void budget() throws Exception {
            mvc.perform(get("/api/v1/avengers/oracle/budget/{p}", "p1"))
                    .andExpect(status().isOk());
        }
    }

    // ─── ArchitectureController ───────────────────────────────────────
    @Nested
    @DisplayName("ArchitectureController")
    class Architecture {
        @Test
        @DisplayName("GET /projects/{id}/architecture returns 404 when project unknown")
        void architectureUnknown() throws Exception {
            mvc.perform(get("/api/v1/projects/{p}/architecture", "no-such"))
                    .andExpect(status().isNotFound());
        }
    }

    // ─── PrController ─────────────────────────────────────────────────
    @Nested
    @DisplayName("PrController")
    class Prs {
        @Test
        @DisplayName("GET /pr returns list (empty by default)")
        void listEmpty() throws Exception {
            mvc.perform(get("/api/v1/pr"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /pr/{id} 404 for unknown")
        void getUnknown() throws Exception {
            mvc.perform(get("/api/v1/pr/{id}", UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("GET /pr/{id} returns PR JSON for known record")
        void getKnown() throws Exception {
            PullRequestRecord rec = new PullRequestRecord();
            rec.setSessionId(UUID.randomUUID());
            rec.setRepoUrl("https://github.com/o/r");
            rec.setBaseBranch("main");
            rec.setStatus(PrStatus.CREATED);
            rec.setProjectId("p1");
            rec = prRepository.save(rec);

            mvc.perform(get("/api/v1/pr/{id}", rec.getId()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.repoUrl").value("https://github.com/o/r"));
        }
    }

    // ─── LearningController ───────────────────────────────────────────
    @Nested
    @DisplayName("LearningController")
    class Learning {
        @Test
        @DisplayName("GET /learning/events returns array")
        void listEvents() throws Exception {
            mvc.perform(get("/api/v1/learning/events"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }

        @Test
        @DisplayName("GET /learning/events/pr/{id} returns array")
        void listEventsByPr() throws Exception {
            mvc.perform(get("/api/v1/learning/events/pr/{id}", UUID.randomUUID()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }

        @Test
        @DisplayName("GET /pr/{id}/remediations returns array")
        void listRemediations() throws Exception {
            mvc.perform(get("/api/v1/pr/{id}/remediations", UUID.randomUUID()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }
    }
}
