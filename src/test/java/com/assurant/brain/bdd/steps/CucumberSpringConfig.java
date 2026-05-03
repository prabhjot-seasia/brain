package com.assurant.brain.bdd.steps;

import com.assurant.brain.TestPostgresContainer;
import com.assurant.brain.bdd.http.BrainHttpClientConfig;
import com.assurant.brain.docs.DocGeneratorService;
import com.assurant.brain.graph.CrossRepoEdgeBuilder;
import com.assurant.brain.graph.repository.ConventionNodeRepository;
import com.assurant.brain.graph.repository.CommunitySummaryNodeRepository;
import com.assurant.brain.graph.repository.IncidentNodeRepository;
import com.assurant.brain.graph.repository.RuntimeServiceEdgeNodeRepository;
import com.assurant.brain.graph.repository.SymbolReferenceNodeRepository;
import com.assurant.brain.graph.repository.ProjectNodeRepository;
import com.assurant.brain.graph.repository.ReviewPatternNodeRepository;
import com.assurant.brain.graph.repository.SLONodeRepository;
import com.assurant.brain.graph.repository.TestRunNodeRepository;
import com.assurant.brain.service.ClarifierService;
import com.assurant.brain.service.GitCloneService;
import com.assurant.brain.service.IngestionService;
import com.assurant.brain.service.PlannerService;
import com.assurant.brain.service.ProjectAffinityDetector;
import com.assurant.brain.service.ProjectDetector;
import io.cucumber.java.After;
import io.cucumber.spring.CucumberContextConfiguration;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@CucumberContextConfiguration
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({BrainHttpClientConfig.class, com.assurant.brain.config.TestSecurityConfig.class})
public class CucumberSpringConfig {

    private static final PostgreSQLContainer<?> POSTGRES = TestPostgresContainer.getInstance();

    @DynamicPropertySource
    static void postgresProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean public ClarifierService clarifierService;
    @MockitoBean public PlannerService plannerService;
    @MockitoBean public IngestionService ingestionService;
    @MockitoBean public GitCloneService gitCloneService;
    @MockitoBean public ProjectDetector projectDetector;
    @MockitoBean public ProjectNodeRepository projectNodeRepository;
    @MockitoBean public ConventionNodeRepository conventionNodeRepository;
    @MockitoBean public IncidentNodeRepository incidentNodeRepository;
    @MockitoBean public ReviewPatternNodeRepository reviewPatternNodeRepository;
    @MockitoBean public TestRunNodeRepository testRunNodeRepository;
    @MockitoBean public SLONodeRepository sloNodeRepository;
    @MockitoBean public CommunitySummaryNodeRepository communitySummaryNodeRepository;
    @MockitoBean public SymbolReferenceNodeRepository symbolReferenceNodeRepository;
    @MockitoBean public RuntimeServiceEdgeNodeRepository runtimeServiceEdgeNodeRepository;
    @MockitoBean public com.assurant.brain.graph.repository.BddScenarioNodeRepository bddScenarioNodeRepository;
    @MockitoBean public CrossRepoEdgeBuilder crossRepoEdgeBuilder;
    @MockitoBean public Neo4jClient neo4jClient;
    @MockitoBean public ProjectAffinityDetector projectAffinityDetector;
    @MockitoBean public VectorStore vectorStore;
    @MockitoBean public DocGeneratorService docGeneratorService;

    @Autowired public MockMvc mvc;
    @Autowired public JdbcTemplate jdbcTemplate;

    private static final String[] TABLES_TO_TRUNCATE = {
            "avenger_reviews",
            "learning_events",
            "ci_remediation_attempts",
            "code_review_iterations",
            "pull_request_records",
            "pr_batch",
            "ticket_proposals",
            "intake_records",
            "generated_documents",
            "solution_patterns",
            "token_usage_records",
            "user_sessions",
            "clarification_sessions",
            "chunks",
            "projects"
    };

    @After(order = 10000)
    public void cleanDb() {
        if (jdbcTemplate == null) return;
        StringBuilder sql = new StringBuilder("TRUNCATE TABLE ");
        for (int i = 0; i < TABLES_TO_TRUNCATE.length; i++) {
            if (i > 0) sql.append(", ");
            sql.append(TABLES_TO_TRUNCATE[i]);
        }
        sql.append(" RESTART IDENTITY CASCADE");
        try {
            jdbcTemplate.execute(sql.toString());
        } catch (org.springframework.dao.DataAccessException e) {
            // Some tables may not exist yet on early-changelog scenarios; fall back to per-table best-effort.
            for (String table : TABLES_TO_TRUNCATE) {
                try {
                    jdbcTemplate.execute("TRUNCATE TABLE " + table + " RESTART IDENTITY CASCADE");
                } catch (org.springframework.dao.DataAccessException ignored) {
                    // table absent — skip
                }
            }
        }
    }
}
