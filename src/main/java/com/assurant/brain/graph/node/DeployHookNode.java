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
@Node("DeployHook")
public class DeployHookNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("phase")
    private String phase;

    @Property("scriptPath")
    private String scriptPath;

    @Property("runas")
    private String runas;

    @Property("sourceFile")
    private String sourceFile;
}
