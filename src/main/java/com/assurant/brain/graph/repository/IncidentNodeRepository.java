package com.assurant.brain.graph.repository;

import com.assurant.brain.graph.node.IncidentNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface IncidentNodeRepository extends Neo4jRepository<IncidentNode, String> {

    @Query("""
            MATCH (p:Project {id: $projectId})-[:HAS_INCIDENT]->(i:Incident)
            RETURN i
            ORDER BY i.occurredAt DESC
            """)
    List<IncidentNode> findByProjectId(@Param("projectId") String projectId);

    @Query("""
            MATCH (p:Project {id: $projectId})-[:HAS_INCIDENT]->(i:Incident)
            WHERE any(hint IN i.affectedClassHints WHERE toLower(hint) CONTAINS toLower($keyword))
            RETURN i
            ORDER BY i.occurredAt DESC
            LIMIT 10
            """)
    List<IncidentNode> findByProjectIdAndClassHintMatch(
            @Param("projectId") String projectId,
            @Param("keyword") String keyword);
}
