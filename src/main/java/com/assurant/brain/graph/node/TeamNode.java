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
@Node("Team")
public class TeamNode {

    @Id
    private String id;

    @Property("name")
    private String name;

    @Property("source")
    private String source;

    @Property("members")
    private List<String> members = new ArrayList<>();

    @Property("ownedPaths")
    private List<String> ownedPaths = new ArrayList<>();
}
