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
@Node("ApiEndpointMap")
public class ApiEndpointMapNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("alias")
    private String alias;

    @Property("relativePath")
    private String relativePath;

    @Property("versionRange")
    private String versionRange;

    @Property("sourceProperty")
    private String sourceProperty;
}
