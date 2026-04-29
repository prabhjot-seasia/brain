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
@Node("C4Workspace")
public class C4WorkspaceNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("workspaceName")
    private String workspaceName;

    @Property("sourceFile")
    private String sourceFile;

    @Property("systems")
    private List<String> systems = new ArrayList<>();

    @Property("containers")
    private List<String> containers = new ArrayList<>();

    @Property("components")
    private List<String> components = new ArrayList<>();

    @Property("persons")
    private List<String> persons = new ArrayList<>();
}
