package com.assurant.brain.graph.repository;

import com.assurant.brain.graph.node.ClassNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Transactional(readOnly = true)
public interface EndpointSummaryRepository extends Neo4jRepository<ClassNode, Long> {

    @Query("""
            MATCH (c:Class {projectId: $projectId})
            WHERE c.qualifiedName ENDS WITH 'Controller'
              AND NOT coalesce(c.filePath, '') CONTAINS '/src/test/'
              AND NOT coalesce(c.filePath, '') CONTAINS '/src/it/'
              AND NOT c.qualifiedName ENDS WITH 'IT'
              AND NOT c.qualifiedName ENDS WITH 'Test'
              AND NOT c.qualifiedName ENDS WITH 'Tests'
            RETURN c.qualifiedName AS qualifiedName, c.filePath AS filePath
            ORDER BY c.qualifiedName
            """)
    List<Map<String, Object>> findControllersByProjectId(@Param("projectId") String projectId);
}
