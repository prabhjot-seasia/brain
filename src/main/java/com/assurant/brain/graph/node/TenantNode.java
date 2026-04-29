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
@Node("Tenant")
public class TenantNode {

    @Id
    private String id;

    @Property("name")
    private String name;

    @Property("displayName")
    private String displayName;

    @Property("source")
    private String source;
}
