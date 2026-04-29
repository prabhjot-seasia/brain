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
@Node("BuildVariant")
public class BuildVariantNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("name")
    private String name;

    @Property("target")
    private String target;

    @Property("targetValue")
    private String targetValue;

    @Property("sourceFile")
    private String sourceFile;
}
