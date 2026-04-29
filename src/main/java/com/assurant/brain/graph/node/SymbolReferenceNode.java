package com.assurant.brain.graph.node;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

@Node("SymbolReference")
@Getter
@Setter
@ToString
public class SymbolReferenceNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("symbolFqn")
    private String symbolFqn;

    @Property("kind")
    private String kind;

    @Property("language")
    private String language;

    @Property("scipScheme")
    private String scipScheme;

    @Property("declaredInClassFqn")
    private String declaredInClassFqn;
}
