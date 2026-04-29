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
@Node("SbomComponent")
public class SbomComponentNode {

    @Id
    private String id;

    @Property("purl")
    private String purl;

    @Property("name")
    private String name;

    @Property("version")
    private String version;

    @Property("componentType")
    private String componentType;

    @Property("licenses")
    private List<String> licenses = new ArrayList<>();

    @Property("directDependency")
    private boolean directDependency;
}
