package com.assurant.brain.graph.repository;

import com.assurant.brain.graph.node.ReviewPatternNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface ReviewPatternNodeRepository extends Neo4jRepository<ReviewPatternNode, String> {

    @Query("""
            MATCH (p:Project {id: $projectId})-[:HAS_REVIEW_PATTERN]->(r:ReviewPattern)
            WHERE r.status IN $statuses
            RETURN r
            ORDER BY r.occurrences DESC
            LIMIT $limit
            """)
    List<ReviewPatternNode> findByProjectIdAndStatusOrderByOccurrences(
            @Param("projectId") String projectId,
            @Param("statuses") List<String> statuses,
            @Param("limit") int limit);
}
