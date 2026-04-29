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
@Node("FeatureFlag")
public class FeatureFlagNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("name")
    private String name;

    @Property("source")
    private String source;

    @Property("flagType")
    private String flagType;

    @Property("defaultValue")
    private String defaultValue;

    @Property("rolloutStatus")
    private String rolloutStatus;

    @Property("references")
    private List<String> references = new ArrayList<>();
}
