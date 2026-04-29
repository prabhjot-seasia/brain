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
@Node("Service")
public class ServiceNode {

    @Id
    private String id;

    @Property("name")
    private String name;

    @Property("baseUrlTemplate")
    private String baseUrlTemplate;

    @Property("inferredProjectId")
    private String inferredProjectId;

    @Property("source")
    private String source;
}
