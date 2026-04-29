package com.assurant.brain.graph.repository;

import com.assurant.brain.graph.node.TestRunNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface TestRunNodeRepository extends Neo4jRepository<TestRunNode, String> {

    @Query("""
            MATCH (p:Project {id: $projectId})-[:HAS_TEST_RUN]->(t:TestRun)
            WHERE t.flakinessScore >= $threshold
            RETURN t
            ORDER BY t.flakinessScore DESC
            LIMIT $limit
            """)
    List<TestRunNode> findFlakyTests(
            @Param("projectId") String projectId,
            @Param("threshold") double threshold,
            @Param("limit") int limit);
}
