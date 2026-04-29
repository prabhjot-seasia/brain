package com.assurant.brain.graph.repository;

import com.assurant.brain.graph.node.CommunitySummaryNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface CommunitySummaryNodeRepository extends Neo4jRepository<CommunitySummaryNode, String> {

    @Query("""
            MATCH (s:CommunitySummary {projectId: $projectId})
            RETURN s
            ORDER BY s.level ASC, s.communityKey ASC
            """)
    List<CommunitySummaryNode> findByProjectId(@Param("projectId") String projectId);

    Optional<CommunitySummaryNode> findByProjectIdAndCommunityKey(String projectId, String communityKey);
}
