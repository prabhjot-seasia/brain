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
@Node("CodePropertyGraph")
public class CodePropertyGraphNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("format")
    private String format;

    @Property("artifactPath")
    private String artifactPath;

    @Property("classFqnHint")
    private String classFqnHint;

    @Property("capturedAt")
    private String capturedAt;
}
