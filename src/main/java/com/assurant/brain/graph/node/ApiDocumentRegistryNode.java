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
@Node("ApiDocumentRegistry")
public class ApiDocumentRegistryNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("masterSpecPath")
    private String masterSpecPath;

    @Property("domains")
    private List<String> domains = new ArrayList<>();
}
