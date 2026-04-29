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
@Node("Issue")
public class IssueNode {

    @Id
    private String id;

    @Property("source")
    private String source;

    @Property("externalId")
    private String externalId;

    @Property("title")
    private String title;

    @Property("status")
    private String status;

    @Property("url")
    private String url;

    @Property("labels")
    private List<String> labels = new ArrayList<>();
}
