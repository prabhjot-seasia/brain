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
@Node("Endpoint")
public class EndpointNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("path")
    private String path;

    @Property("httpMethod")
    private String httpMethod;

    @Property("source")
    private String source;
}
