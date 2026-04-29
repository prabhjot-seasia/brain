package com.assurant.brain.graph.node;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

@Getter
@Setter
@ToString
@Node("TestRun")
public class TestRunNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("testFqn")
    private String testFqn;

    @Property("workflowName")
    private String workflowName;

    @Property("totalRuns")
    private int totalRuns;

    @Property("passCount")
    private int passCount;

    @Property("failCount")
    private int failCount;

    @Property("flakinessScore")
    private double flakinessScore;

    @Property("lastObservedAt")
    private String lastObservedAt;
}
