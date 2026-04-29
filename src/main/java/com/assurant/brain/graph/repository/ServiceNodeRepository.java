package com.assurant.brain.graph.repository;

import com.assurant.brain.graph.node.ServiceNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Transactional(readOnly = true)
public interface ServiceNodeRepository extends Neo4jRepository<ServiceNode, String> {

    Optional<ServiceNode> findById(String id);

    @Query("""
            MATCH (s:Service)
            WHERE toLower(s.name) = toLower($name)
            RETURN s
            """)
    List<ServiceNode> findByNameIgnoreCase(@Param("name") String name);

    @Query("""
            MATCH (p:Project)-[:CALLS_SERVICE]->(s:Service)
            WHERE p.id = $projectId
            RETURN s
            """)
    List<ServiceNode> findCalledByProject(@Param("projectId") String projectId);
}
