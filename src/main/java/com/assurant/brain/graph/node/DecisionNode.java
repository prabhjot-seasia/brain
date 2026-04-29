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
@Node("Decision")
public class DecisionNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("source")
    private String source;

    @Property("title")
    private String title;

    @Property("status")
    private String status;

    @Property("date")
    private String date;

    @Property("supersedes")
    private String supersedes;

    @Property("filePath")
    private String filePath;
}
