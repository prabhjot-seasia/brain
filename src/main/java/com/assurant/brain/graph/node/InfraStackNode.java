package com.assurant.brain.graph.node;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ToString
@Node("InfraStack")
public class InfraStackNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("name")
    private String name;

    @Property("infraType")
    private String infraType;

    @Property("path")
    private String path;

    @Property("constructTypes")
    private List<String> constructTypes = new ArrayList<>();
}
