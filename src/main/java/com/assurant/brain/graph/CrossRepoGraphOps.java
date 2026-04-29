package com.assurant.brain.graph;

import lombok.RequiredArgsConstructor;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
class CrossRepoGraphOps {

    private static final String LOAD_IDENTITY_CYPHER = """
            MATCH (p:Project {id: $projectId})
            RETURN p.id AS id, p.groupId AS groupId, p.artifactId AS artifactId, p.npmName AS npmName
            """;

    private static final String CLEAR_OUTGOING_CYPHER = """
            MATCH (p:Project {id: $projectId})-[r:DEPENDS_ON]->()
            DELETE r
            """;

    private static final String CLEAR_INCOMING_CYPHER = """
            MATCH ()-[r:DEPENDS_ON]->(p:Project {id: $projectId})
            DELETE r
            """;

    private static final String FIND_OUTGOING_MATCHES_CYPHER = """
            MATCH (me:Project {id: $projectId})-[:USES_LIBRARY]->(l:Library)
            MATCH (other:Project)
            WHERE other.id <> $projectId
              AND (
                (other.groupId IS NOT NULL AND other.artifactId IS NOT NULL
                 AND l.groupId = other.groupId AND l.artifactId = other.artifactId)
                OR
                (other.npmName IS NOT NULL AND l.artifactId = other.npmName)
              )
            RETURN DISTINCT other.id AS id
            """;

    private static final String FIND_INCOMING_MATCHES_CYPHER = """
            MATCH (other:Project)-[:USES_LIBRARY]->(l:Library)
            WHERE other.id <> $projectId
              AND (
                ($groupId IS NOT NULL AND $artifactId IS NOT NULL
                 AND l.groupId = $groupId AND l.artifactId = $artifactId)
                OR
                ($npmName IS NOT NULL AND l.artifactId = $npmName)
              )
            RETURN DISTINCT other.id AS id
            """;

    private static final String CREATE_EDGE_CYPHER = """
            MATCH (from:Project {id: $fromId}), (to:Project {id: $toId})
            MERGE (from)-[:DEPENDS_ON]->(to)
            """;

    private static final String LIST_PROJECT_IDS_CYPHER = """
            MATCH (p:Project) RETURN p.id AS id
            """;

    private static final String LINK_SERVICES_TO_PROJECTS_CYPHER = """
            MATCH (s:Service)
            WHERE s.inferredProjectId IS NULL
            OPTIONAL MATCH (p:Project)
              WHERE toLower(p.name)       = toLower(s.name)
                 OR toLower(p.artifactId) = toLower(s.name)
                 OR toLower(p.npmName)    = toLower(s.name)
                 OR toLower(p.id)         = toLower(s.name)
            WITH s, p WHERE p IS NOT NULL
            SET s.inferredProjectId = p.id
            WITH s, p
            MERGE (s)-[:IMPLEMENTED_BY]->(p)
            RETURN count(*) AS linked
            """;

    private static final String CLEAR_SERVICE_INFERENCE_FOR_CYPHER = """
            MATCH (p:Project {id: $projectId})<-[r:IMPLEMENTED_BY]-(s:Service)
            DELETE r
            SET s.inferredProjectId = null
            """;

    private final Neo4jClient neo4jClient;

    Optional<CrossRepoEdgeBuilder.ProjectIdentity> loadIdentity(String projectId) {
        return neo4jClient.query(LOAD_IDENTITY_CYPHER)
                .bind(projectId).to("projectId")
                .fetchAs(CrossRepoEdgeBuilder.ProjectIdentity.class)
                .mappedBy((ts, record) -> new CrossRepoEdgeBuilder.ProjectIdentity(
                        record.get("id").asString(null),
                        record.get("groupId").asString(null),
                        record.get("artifactId").asString(null),
                        record.get("npmName").asString(null)
                ))
                .one();
    }

    void clearDependenciesFor(String projectId) {
        Map<String, Object> params = Map.of("projectId", projectId);
        neo4jClient.query(CLEAR_OUTGOING_CYPHER).bindAll(params).run();
        neo4jClient.query(CLEAR_INCOMING_CYPHER).bindAll(params).run();
    }

    Collection<String> findOutgoingMatches(String projectId) {
        return neo4jClient.query(FIND_OUTGOING_MATCHES_CYPHER)
                .bind(projectId).to("projectId")
                .fetchAs(String.class)
                .mappedBy((ts, record) -> record.get("id").asString())
                .all();
    }

    Collection<String> findIncomingMatches(CrossRepoEdgeBuilder.ProjectIdentity target) {
        Map<String, Object> params = new HashMap<>();
        params.put("projectId",  target.id());
        params.put("groupId",    target.groupId());
        params.put("artifactId", target.artifactId());
        params.put("npmName",    target.npmName());
        return neo4jClient.query(FIND_INCOMING_MATCHES_CYPHER)
                .bindAll(params)
                .fetchAs(String.class)
                .mappedBy((ts, record) -> record.get("id").asString())
                .all();
    }

    void createEdge(String fromId, String toId) {
        Map<String, Object> params = Map.of("fromId", fromId, "toId", toId);
        neo4jClient.query(CREATE_EDGE_CYPHER).bindAll(params).run();
    }

    Collection<String> listAllProjectIds() {
        return neo4jClient.query(LIST_PROJECT_IDS_CYPHER)
                .fetchAs(String.class)
                .mappedBy((ts, record) -> record.get("id").asString())
                .all();
    }

    void clearServiceInferenceFor(String projectId) {
        neo4jClient.query(CLEAR_SERVICE_INFERENCE_FOR_CYPHER)
                .bind(projectId).to("projectId")
                .run();
    }

    int linkServicesToProjects() {
        return neo4jClient.query(LINK_SERVICES_TO_PROJECTS_CYPHER)
                .fetchAs(Integer.class)
                .mappedBy((ts, record) -> record.get("linked").asInt(0))
                .one()
                .orElse(0);
    }
}
