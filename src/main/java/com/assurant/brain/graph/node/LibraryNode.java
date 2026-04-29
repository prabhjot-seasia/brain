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
@Node("Library")
public class LibraryNode {

    @Id
    private String name;

    @Property("groupId")
    private String groupId;

    @Property("artifactId")
    private String artifactId;

    @Property("version")
    private String version;

    @Property("purpose")
    private String purpose;
}
