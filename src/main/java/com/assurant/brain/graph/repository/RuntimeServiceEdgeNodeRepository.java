package com.assurant.brain.graph.repository;

import com.assurant.brain.graph.node.RuntimeServiceEdgeNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface RuntimeServiceEdgeNodeRepository extends Neo4jRepository<RuntimeServiceEdgeNode, String> {

    @Query("""
            MATCH (e:RuntimeServiceEdge {projectId: $projectId})
            RETURN e ORDER BY e.frequency DESC
            LIMIT 500
            """)
    List<RuntimeServiceEdgeNode> findByProjectIdOrderByFrequencyDesc(@Param("projectId") String projectId);
}
