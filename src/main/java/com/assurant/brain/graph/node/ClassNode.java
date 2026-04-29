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
@Node("Class")
public class ClassNode {

    @Id @GeneratedValue
    private Long id;

    @Property("name")
    private String name;

    @Property("qualifiedName")
    private String qualifiedName;

    @Property("filePath")
    private String filePath;

    @Property("classType")
    private String classType;

    @Property("projectId")
    private String projectId;

    @Relationship(type = "IMPORTS", direction = Relationship.Direction.OUTGOING)
    private List<LibraryNode> imports = new ArrayList<>();

    @Relationship(type = "CALLS", direction = Relationship.Direction.OUTGOING)
    private List<ClassNode> calls = new ArrayList<>();
}
