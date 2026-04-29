package com.assurant.brain.graph.repository;

import com.assurant.brain.graph.node.ConventionNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface ConventionNodeRepository extends Neo4jRepository<ConventionNode, Long> {

    List<ConventionNode> findByProjectId(String projectId);

    List<ConventionNode> findByProjectIdAndCategory(String projectId, String category);

    List<ConventionNode> findByProjectIdOrderByTrustWeightDesc(String projectId);

    @Query("""
            MATCH (c:Convention)
            WHERE c.projectId = $projectId AND c.sourceFile STARTS WITH $sourcePrefix
            RETURN c
            """)
    List<ConventionNode> findByProjectIdAndSourceFilePrefix(
            @Param("projectId") String projectId,
            @Param("sourcePrefix") String sourcePrefix);

    @Transactional
    @Query("""
            MATCH (c:Convention)
            WHERE c.projectId = $projectId AND c.sourceFile STARTS WITH $sourcePrefix
            DETACH DELETE c
            """)
    void deleteByProjectIdAndSourceFilePrefix(
            @Param("projectId") String projectId,
            @Param("sourcePrefix") String sourcePrefix);
}
