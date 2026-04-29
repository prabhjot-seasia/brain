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
@Node("TestCase")
public class TestCaseNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("source")
    private String source;

    @Property("externalId")
    private String externalId;

    @Property("title")
    private String title;

    @Property("priority")
    private String priority;

    @Property("status")
    private String status;

    @Property("gherkin")
    private String gherkin;

    @Property("verifiesEndpointIds")
    private List<String> verifiesEndpointIds = new ArrayList<>();

    @Property("lastExecuted")
    private String lastExecuted;
}
