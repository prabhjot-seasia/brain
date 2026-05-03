package com.assurant.brain.graph.node;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ToString
@Node("BddScenario")
public class BddScenarioNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("featureFile")
    private String featureFile;

    @Property("featureTitle")
    private String featureTitle;

    @Property("scenarioTitle")
    private String scenarioTitle;

    @Property("tags")
    private List<String> tags = new ArrayList<>();

    @Property("steps")
    private List<String> steps = new ArrayList<>();

    @Property("coversProjectId")
    private String coversProjectId;
}
