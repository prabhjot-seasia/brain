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
@Node("ScheduledScript")
public class ScheduledScriptNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("path")
    private String path;

    @Property("scriptType")
    private String scriptType;

    @Property("cronExpression")
    private String cronExpression;

    @Property("purpose")
    private String purpose;
}
