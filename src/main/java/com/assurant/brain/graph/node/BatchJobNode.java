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
@Node("BatchJob")
public class BatchJobNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("jobName")
    private String jobName;

    @Property("framework")
    private String framework;

    @Property("trigger")
    private String trigger;

    @Property("sourceFile")
    private String sourceFile;
}
