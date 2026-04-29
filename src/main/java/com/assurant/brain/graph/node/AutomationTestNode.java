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
@Node("AutomationTest")
public class AutomationTestNode {

    @Id
    private String id;

    @Property("repoId")
    private String repoId;

    @Property("framework")
    private String framework;

    @Property("path")
    private String path;

    @Property("testFqn")
    private String testFqn;

    @Property("exercisesEndpointIds")
    private List<String> exercisesEndpointIds = new ArrayList<>();

    @Property("lastRunStatus")
    private String lastRunStatus;
}
