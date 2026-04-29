package com.assurant.brain.graph.repository;

import com.assurant.brain.graph.node.SLONode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface SLONodeRepository extends Neo4jRepository<SLONode, String> {

    @Query("""
            MATCH (p:Project {id: $projectId})-[:HAS_SLO]->(s:SLO)
            RETURN s
            ORDER BY s.targetPercent DESC
            """)
    List<SLONode> findByProjectIdOrderByTargetDesc(@Param("projectId") String projectId);
}
