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
@Node("EcsServiceConfig")
public class EcsServiceConfigNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("cpu")
    private String cpu;

    @Property("memory")
    private String memory;

    @Property("desiredCount")
    private int desiredCount;

    @Property("healthCheckPath")
    private String healthCheckPath;

    @Property("scalingPolicySummary")
    private String scalingPolicySummary;

    @Property("sourceFile")
    private String sourceFile;
}
