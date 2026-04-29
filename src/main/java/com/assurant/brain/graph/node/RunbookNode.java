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
@Node("Runbook")
public class RunbookNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("title")
    private String title;

    @Property("filePath")
    private String filePath;

    @Property("triggers")
    private List<String> triggers = new ArrayList<>();

    @Property("targetServices")
    private List<String> targetServices = new ArrayList<>();
}
