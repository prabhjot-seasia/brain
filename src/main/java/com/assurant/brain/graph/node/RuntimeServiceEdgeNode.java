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
@Node("RuntimeServiceEdge")
public class RuntimeServiceEdgeNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("fromServiceName")
    private String fromServiceName;

    @Property("toServiceName")
    private String toServiceName;

    @Property("frequency")
    private long frequency;

    @Property("p50LatencyMs")
    private double p50LatencyMs;

    @Property("p99LatencyMs")
    private double p99LatencyMs;

    @Property("errorRate")
    private double errorRate;

    @Property("source")
    private String source;

    @Property("sampledFromHours")
    private int sampledFromHours;

    @Property("capturedAt")
    private String capturedAt;
}
