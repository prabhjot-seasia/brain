package com.assurant.brain.graph.repository;

import com.assurant.brain.graph.node.QueueNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface QueueNodeRepository extends Neo4jRepository<QueueNode, String> {

    Optional<QueueNode> findById(String id);

    @Query("""
            MATCH (q:Queue)
            WHERE toLower(q.name) = toLower($name) AND q.queueType = $queueType
            RETURN q
            """)
    List<QueueNode> findByNameAndType(@Param("name") String name, @Param("queueType") String queueType);

    @Query("""
            MATCH (p:Project)-[:PUBLISHES_TO|CONSUMES_FROM]->(q:Queue)
            WHERE p.id = $projectId
            RETURN q
            """)
    List<QueueNode> findUsedByProject(@Param("projectId") String projectId);
}
