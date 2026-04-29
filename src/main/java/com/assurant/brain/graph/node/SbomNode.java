package com.assurant.brain.graph.node;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;
import org.springframework.data.neo4j.core.schema.Relationship;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@ToString
@Node("Sbom")
public class SbomNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("format")
    private String format;

    @Property("specVersion")
    private String specVersion;

    @Property("sourcePath")
    private String sourcePath;

    @Property("capturedAt")
    private String capturedAt;

    @Relationship(type = "CONTAINS", direction = Relationship.Direction.OUTGOING)
    private List<SbomComponentNode> components = new ArrayList<>();
}
