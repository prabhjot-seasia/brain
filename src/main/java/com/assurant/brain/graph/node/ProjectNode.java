package com.assurant.brain.graph.node;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ToString
@Node("Project")
public class ProjectNode {

    @Id
    private String id;

    @Property("name")
    private String name;

    @Property("language")
    private String language;

    @Property("framework")
    private String framework;

    @Property("buildTool")
    private String buildTool;

    @Property("groupId")
    private String groupId;

    @Property("artifactId")
    private String artifactId;

    @Property("npmName")
    private String npmName;

    @Property("kind")
    private String kind;

    @Relationship(type = "HAS_MODULE", direction = Relationship.Direction.OUTGOING)
    private List<ModuleNode> modules = new ArrayList<>();

    @Relationship(type = "USES_LIBRARY", direction = Relationship.Direction.OUTGOING)
    private List<LibraryNode> libraries = new ArrayList<>();

    @Relationship(type = "FOLLOWS_CONVENTION", direction = Relationship.Direction.OUTGOING)
    private List<ConventionNode> conventions = new ArrayList<>();

    @Relationship(type = "DEPENDS_ON", direction = Relationship.Direction.OUTGOING)
    private List<ProjectNode> dependencies = new ArrayList<>();

    @Relationship(type = "HAS_DECISION", direction = Relationship.Direction.OUTGOING)
    private List<DecisionNode> decisions = new ArrayList<>();

    @Relationship(type = "OWNED_BY", direction = Relationship.Direction.OUTGOING)
    private List<TeamNode> owners = new ArrayList<>();

    @Relationship(type = "HAS_SBOM", direction = Relationship.Direction.OUTGOING)
    private List<SbomNode> sboms = new ArrayList<>();

    @Relationship(type = "HAS_SLO", direction = Relationship.Direction.OUTGOING)
    private List<SLONode> slos = new ArrayList<>();

    @Relationship(type = "CALLS_SERVICE", direction = Relationship.Direction.OUTGOING)
    private List<ServiceNode> calledServices = new ArrayList<>();

    @Relationship(type = "PUBLISHES_TO", direction = Relationship.Direction.OUTGOING)
    private List<QueueNode> publishedQueues = new ArrayList<>();

    @Relationship(type = "CONSUMES_FROM", direction = Relationship.Direction.OUTGOING)
    private List<QueueNode> consumedQueues = new ArrayList<>();

    @Relationship(type = "EXPOSES_ENDPOINT", direction = Relationship.Direction.OUTGOING)
    private List<EndpointNode> exposedEndpoints = new ArrayList<>();

    @Relationship(type = "HAS_ENVIRONMENT", direction = Relationship.Direction.OUTGOING)
    private List<EnvironmentNode> environments = new ArrayList<>();

    @Relationship(type = "SERVES_TENANT", direction = Relationship.Direction.OUTGOING)
    private List<TenantNode> tenants = new ArrayList<>();

    @Relationship(type = "HAS_CONFIG_KEY", direction = Relationship.Direction.OUTGOING)
    private List<ConfigKeyNode> configKeys = new ArrayList<>();

    @Relationship(type = "OWNS_TABLE", direction = Relationship.Direction.OUTGOING)
    private List<DatabaseTableNode> ownedTables = new ArrayList<>();

    @Relationship(type = "DEFINED_BY_CONTRACT", direction = Relationship.Direction.OUTGOING)
    private List<ExternalApiContractNode> contracts = new ArrayList<>();

    @Relationship(type = "MAPS_ENDPOINT", direction = Relationship.Direction.OUTGOING)
    private List<ApiEndpointMapNode> apiEndpointMap = new ArrayList<>();

    @Relationship(type = "HAS_BATCH_JOB", direction = Relationship.Direction.OUTGOING)
    private List<BatchJobNode> batchJobs = new ArrayList<>();

    @Relationship(type = "HAS_SCHEDULED_SCRIPT", direction = Relationship.Direction.OUTGOING)
    private List<ScheduledScriptNode> scheduledScripts = new ArrayList<>();

    @Relationship(type = "REGISTERS_API_DOCUMENT", direction = Relationship.Direction.OUTGOING)
    private ApiDocumentRegistryNode apiDocumentRegistry;

    @Relationship(type = "HAS_STACK", direction = Relationship.Direction.OUTGOING)
    private List<InfraStackNode> infraStacks = new ArrayList<>();

    @Relationship(type = "RUNS_AS", direction = Relationship.Direction.OUTGOING)
    private EcsServiceConfigNode ecsServiceConfig;

    @Relationship(type = "BUILDS_AS", direction = Relationship.Direction.OUTGOING)
    private List<BuildVariantNode> buildVariants = new ArrayList<>();

    @Relationship(type = "DEPLOYS_WITH_HOOK", direction = Relationship.Direction.OUTGOING)
    private List<DeployHookNode> deployHooks = new ArrayList<>();

    @Relationship(type = "CI_WORKFLOW", direction = Relationship.Direction.OUTGOING)
    private List<WorkflowNode> ciWorkflows = new ArrayList<>();

    @Relationship(type = "HAS_INCIDENT", direction = Relationship.Direction.OUTGOING)
    private List<IncidentNode> incidents = new ArrayList<>();

    @Relationship(type = "HAS_RUNBOOK", direction = Relationship.Direction.OUTGOING)
    private List<RunbookNode> runbooks = new ArrayList<>();

    @Relationship(type = "BELONGS_TO_CONTEXT", direction = Relationship.Direction.OUTGOING)
    private BoundedContextNode boundedContext;

    @Relationship(type = "HAS_REVIEW_PATTERN", direction = Relationship.Direction.OUTGOING)
    private List<ReviewPatternNode> reviewPatterns = new ArrayList<>();

    @Relationship(type = "HAS_TEST_RUN", direction = Relationship.Direction.OUTGOING)
    private List<TestRunNode> testRuns = new ArrayList<>();

    @Relationship(type = "GATED_BY_FLAG", direction = Relationship.Direction.OUTGOING)
    private List<FeatureFlagNode> featureFlags = new ArrayList<>();

    @Relationship(type = "HAS_CHAOS_EXPERIMENT", direction = Relationship.Direction.OUTGOING)
    private List<ChaosExperimentNode> chaosExperiments = new ArrayList<>();

    @Relationship(type = "HAS_SLOW_QUERY", direction = Relationship.Direction.OUTGOING)
    private List<SlowQueryNode> slowQueries = new ArrayList<>();

    @Relationship(type = "HAS_LOG_PATTERN", direction = Relationship.Direction.OUTGOING)
    private List<LogPatternNode> logPatterns = new ArrayList<>();

    @Relationship(type = "ATTRIBUTED_TO_COST", direction = Relationship.Direction.OUTGOING)
    private List<CostTagNode> costTags = new ArrayList<>();

    @Relationship(type = "HAS_RUNTIME_EDGE", direction = Relationship.Direction.OUTGOING)
    private List<RuntimeServiceEdgeNode> runtimeServiceEdges = new ArrayList<>();

    @Relationship(type = "HAS_CPG", direction = Relationship.Direction.OUTGOING)
    private List<CodePropertyGraphNode> codePropertyGraphs = new ArrayList<>();

    @Relationship(type = "EXPOSES_EVENT_SCHEMA", direction = Relationship.Direction.OUTGOING)
    private List<EventSchemaNode> eventSchemas = new ArrayList<>();

    @Relationship(type = "EXPOSES_GRAPHQL_SCHEMA", direction = Relationship.Direction.OUTGOING)
    private List<GraphQlSchemaNode> graphQlSchemas = new ArrayList<>();

    @Relationship(type = "HAS_PII_TAG", direction = Relationship.Direction.OUTGOING)
    private List<PiiTagNode> piiTags = new ArrayList<>();

    @Relationship(type = "HAS_SECURITY_FINDING", direction = Relationship.Direction.OUTGOING)
    private List<SecurityFindingNode> securityFindings = new ArrayList<>();

    @Relationship(type = "HAS_C4_WORKSPACE", direction = Relationship.Direction.OUTGOING)
    private C4WorkspaceNode c4Workspace;

    @Relationship(type = "HAS_TEST_CASE", direction = Relationship.Direction.OUTGOING)
    private List<TestCaseNode> testCases = new ArrayList<>();

    @Relationship(type = "HAS_AUTOMATION_TEST", direction = Relationship.Direction.OUTGOING)
    private List<AutomationTestNode> automationTests = new ArrayList<>();

    @Relationship(type = "REALIZES_BUSINESS_PROCESS", direction = Relationship.Direction.OUTGOING)
    private List<BusinessProcessNode> businessProcesses = new ArrayList<>();

    @Relationship(type = "FOLLOWS_TEAM_PROCESS", direction = Relationship.Direction.OUTGOING)
    private TeamProcessNode teamProcess;
}
