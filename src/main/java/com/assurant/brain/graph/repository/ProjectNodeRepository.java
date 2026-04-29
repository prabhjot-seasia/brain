package com.assurant.brain.graph.repository;

import com.assurant.brain.graph.node.ProjectNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface ProjectNodeRepository extends Neo4jRepository<ProjectNode, String> {

    Optional<ProjectNode> findById(String id);

    @Query("""
            MATCH (p:Project {id: $projectId})-[:FOLLOWS_CONVENTION]->(c:Convention)
            WHERE $category IS NULL OR c.category = $category
            RETURN c
            """)
    List<Object> findConventionsByProjectAndCategory(
            @Param("projectId") String projectId,
            @Param("category") String category);

    @Query("""
            MATCH (p:Project {id: $projectId})-[:HAS_MODULE|HAS_CLASS*1..3]->(c:Class)
            WHERE toLower(c.name) CONTAINS toLower($keyword)
               OR toLower(c.qualifiedName) CONTAINS toLower($keyword)
            RETURN c
            LIMIT 20
            """)
    List<Object> findAffectedClasses(
            @Param("projectId") String projectId,
            @Param("keyword") String keyword);

    @Query("""
            MATCH (p:Project {id: $projectId})-[:DEPENDS_ON]->(d:Project)
            RETURN d
            """)
    List<ProjectNode> findDependencies(@Param("projectId") String projectId);

    @Query("""
            MATCH (d:Project)-[:DEPENDS_ON]->(p:Project {id: $projectId})
            RETURN d
            """)
    List<ProjectNode> findDependents(@Param("projectId") String projectId);

    @Query("""
            MATCH (p:Project {id: $projectId})-[:DEPENDS_ON*1..10]->(d:Project)
            WHERE d.id <> $projectId
            RETURN DISTINCT d
            """)
    List<ProjectNode> findTransitiveDependencies(@Param("projectId") String projectId);

    @Query("""
            MATCH (caller:Class)-[:CALLS]->(target:Class)
            WHERE toLower(target.qualifiedName) = toLower($qualifiedName)
               OR toLower(target.name) = toLower($qualifiedName)
            RETURN DISTINCT caller.projectId AS projectId,
                            caller.qualifiedName AS qualifiedName,
                            caller.filePath AS filePath
            """)
    List<java.util.Map<String, Object>> findCallersByQualifiedName(
            @Param("qualifiedName") String qualifiedName);
}
