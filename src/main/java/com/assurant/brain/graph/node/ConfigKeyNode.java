package com.assurant.brain.graph.node;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

@Getter
@Setter
@ToString
@Node("ConfigKey")
public class ConfigKeyNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("key")
    private String key;

    @Property("valueTemplate")
    private String valueTemplate;

    @Property("sourceFile")
    private String sourceFile;

    @Relationship(type = "OVERRIDDEN_FOR", direction = Relationship.Direction.OUTGOING)
    private TenantNode tenantOverride;
}
