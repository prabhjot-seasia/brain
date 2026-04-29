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
@Node("GraphQlSchema")
public class GraphQlSchemaNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("sourceFile")
    private String sourceFile;

    @Property("queryFields")
    private List<String> queryFields = new ArrayList<>();

    @Property("mutationFields")
    private List<String> mutationFields = new ArrayList<>();

    @Property("subscriptionFields")
    private List<String> subscriptionFields = new ArrayList<>();

    @Property("typeNames")
    private List<String> typeNames = new ArrayList<>();
}
