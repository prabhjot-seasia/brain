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
@Node("Queue")
public class QueueNode {

    @Id
    private String id;

    @Property("queueType")
    private String queueType;

    @Property("name")
    private String name;

    @Property("arn")
    private String arn;

    @Property("source")
    private String source;
}
