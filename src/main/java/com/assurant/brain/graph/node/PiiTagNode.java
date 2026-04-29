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
@Node("PiiTag")
public class PiiTagNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("source")
    private String source;

    @Property("category")
    private String category;

    @Property("targetType")
    private String targetType;

    @Property("targetIdentifier")
    private String targetIdentifier;

    @Property("sensitivity")
    private String sensitivity;
}
