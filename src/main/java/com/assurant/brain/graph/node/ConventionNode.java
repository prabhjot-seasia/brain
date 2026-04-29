package com.assurant.brain.graph.node;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

@Getter
@Setter
@ToString
@Node("Convention")
public class ConventionNode {

    @Id @GeneratedValue
    private Long id;

    @Property("rule")
    private String rule;

    @Property("category")
    private String category;

    @Property("projectId")
    private String projectId;

    @Property("sourceFile")
    private String sourceFile;

    @Property("trustWeight")
    private double trustWeight;
}
