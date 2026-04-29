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
@Node("ChaosExperiment")
public class ChaosExperimentNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("name")
    private String name;

    @Property("templateArn")
    private String templateArn;

    @Property("source")
    private String source;

    @Property("knownFailureModes")
    private List<String> knownFailureModes = new ArrayList<>();

    @Property("targetServices")
    private List<String> targetServices = new ArrayList<>();
}
