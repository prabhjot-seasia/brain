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
@Node("BoundedContext")
public class BoundedContextNode {

    @Id
    private String id;

    @Property("name")
    private String name;

    @Property("displayName")
    private String displayName;

    @Property("ownerTeamId")
    private String ownerTeamId;

    @Property("source")
    private String source;

    @Property("includedProjects")
    private List<String> includedProjects = new ArrayList<>();

    @Property("ubiquitousLanguageTerms")
    private List<String> ubiquitousLanguageTerms = new ArrayList<>();
}
