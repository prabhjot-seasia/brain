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
@Node("Workflow")
public class WorkflowNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("name")
    private String name;

    @Property("path")
    private String path;

    @Property("triggers")
    private List<String> triggers = new ArrayList<>();

    @Property("matrixDimensions")
    private List<String> matrixDimensions = new ArrayList<>();

    @Property("usesReusableWorkflows")
    private List<String> usesReusableWorkflows = new ArrayList<>();
}
