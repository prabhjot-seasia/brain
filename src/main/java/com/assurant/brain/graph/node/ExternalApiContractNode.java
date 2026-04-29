package com.assurant.brain.graph.node;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ToString
@Node("ExternalApiContract")
public class ExternalApiContractNode {

    @Id
    private String id;

    @Property("format")
    private String format;

    @Property("serviceName")
    private String serviceName;

    @Property("path")
    private String path;

    @Property("specVersion")
    private String specVersion;

    @Relationship(type = "DEFINES_ENDPOINT", direction = Relationship.Direction.OUTGOING)
    private List<EndpointNode> endpoints = new ArrayList<>();
}
