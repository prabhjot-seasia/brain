package com.assurant.brain.graph.node;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.GeneratedValue;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ToString
@Node("Module")
public class ModuleNode {

    @Id @GeneratedValue
    private Long id;

    @Property("name")
    private String name;

    @Property("packagePrefix")
    private String packagePrefix;

    @Property("projectId")
    private String projectId;

    @Relationship(type = "HAS_CLASS", direction = Relationship.Direction.OUTGOING)
    private List<ClassNode> classes = new ArrayList<>();
}
