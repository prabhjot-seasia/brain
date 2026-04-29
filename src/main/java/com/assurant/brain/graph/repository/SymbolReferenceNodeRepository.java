package com.assurant.brain.graph.repository;

import com.assurant.brain.graph.node.SymbolReferenceNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Transactional(readOnly = true)
public interface SymbolReferenceNodeRepository extends Neo4jRepository<SymbolReferenceNode, String> {

    @Query("""
            MATCH (s:SymbolReference {symbolFqn: $symbolFqn})
            RETURN s
            """)
    List<SymbolReferenceNode> findBySymbolFqn(@Param("symbolFqn") String symbolFqn);

    @Query("""
            MATCH (s:SymbolReference {projectId: $projectId})
            RETURN s
            ORDER BY s.symbolFqn ASC
            """)
    List<SymbolReferenceNode> findByProjectId(@Param("projectId") String projectId);
}
