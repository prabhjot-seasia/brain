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
@Node("SLO")
public class SLONode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("serviceName")
    private String serviceName;

    @Property("indicatorType")
    private String indicatorType;

    @Property("targetPercent")
    private double targetPercent;

    @Property("window")
    private String window;

    @Property("description")
    private String description;

    @Property("sourceFile")
    private String sourceFile;
}
