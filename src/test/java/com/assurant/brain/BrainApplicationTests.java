package com.assurant.brain;

import com.assurant.brain.graph.CrossRepoEdgeBuilder;
import com.assurant.brain.graph.repository.CommunitySummaryNodeRepository;
import com.assurant.brain.graph.repository.IncidentNodeRepository;
import com.assurant.brain.graph.repository.ReviewPatternNodeRepository;
import com.assurant.brain.graph.repository.SLONodeRepository;
import com.assurant.brain.graph.repository.SymbolReferenceNodeRepository;
import com.assurant.brain.graph.repository.TestRunNodeRepository;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@org.springframework.context.annotation.Import(com.assurant.brain.config.TestSecurityConfig.class)
public abstract class BrainApplicationTests {

    private static final PostgreSQLContainer<?> POSTGRES = TestPostgresContainer.getInstance();

    @DynamicPropertySource
    static void postgresProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",      POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @MockitoBean
    public Neo4jClient neo4jClient;

    @MockitoBean
    public CrossRepoEdgeBuilder crossRepoEdgeBuilder;

    @MockitoBean
    public IncidentNodeRepository incidentNodeRepository;

    @MockitoBean
    public ReviewPatternNodeRepository reviewPatternNodeRepository;

    @MockitoBean
    public TestRunNodeRepository testRunNodeRepository;

    @MockitoBean
    public SLONodeRepository sloNodeRepository;

    @MockitoBean
    public CommunitySummaryNodeRepository communitySummaryNodeRepository;

    @MockitoBean
    public SymbolReferenceNodeRepository symbolReferenceNodeRepository;

    @MockitoBean
    public com.assurant.brain.graph.repository.BddScenarioNodeRepository bddScenarioNodeRepository;

    @MockitoBean
    public com.assurant.brain.graph.repository.EndpointSummaryRepository endpointSummaryRepository;

    @Autowired
    public MockMvc mvc;

    @Autowired
    public JdbcTemplate jdbcTemplate;

    @AfterEach
    public void cleanDb() {
        jdbcTemplate.execute("DELETE FROM clarification_sessions");
        jdbcTemplate.execute("DELETE FROM chunks");
        jdbcTemplate.execute("DELETE FROM projects");
    }
}
