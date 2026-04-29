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
@Node("SlowQuery")
public class SlowQueryNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("queryHash")
    private String queryHash;

    @Property("normalizedSql")
    private String normalizedSql;

    @Property("calls")
    private long calls;

    @Property("meanTimeMs")
    private double meanTimeMs;

    @Property("p99TimeMs")
    private double p99TimeMs;

    @Property("totalTimeMs")
    private double totalTimeMs;

    @Property("capturedAt")
    private String capturedAt;
}
