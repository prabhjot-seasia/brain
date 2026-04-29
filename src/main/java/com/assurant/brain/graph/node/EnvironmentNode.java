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
@Node("Environment")
public class EnvironmentNode {

    @Id
    private String id;

    @Property("name")
    private String name;

    @Property("baseDomain")
    private String baseDomain;

    @Property("awsAccount")
    private String awsAccount;
}
