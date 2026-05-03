package com.assurant.brain.graph.repository;

import com.assurant.brain.graph.node.BddScenarioNode;
import org.springframework.data.neo4j.repository.Neo4jRepository;

import java.util.List;

public interface BddScenarioNodeRepository extends Neo4jRepository<BddScenarioNode, String> {

    List<BddScenarioNode> findByProjectId(String projectId);

    List<BddScenarioNode> findByCoversProjectId(String coversProjectId);
}
