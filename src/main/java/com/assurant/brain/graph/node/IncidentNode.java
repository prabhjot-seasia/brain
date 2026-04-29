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
@Node("Incident")
public class IncidentNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("title")
    private String title;

    @Property("severity")
    private String severity;

    @Property("occurredAt")
    private String occurredAt;

    @Property("status")
    private String status;

    @Property("rootCauseSummary")
    private String rootCauseSummary;

    @Property("filePath")
    private String filePath;

    @Property("affectedClassHints")
    private List<String> affectedClassHints = new ArrayList<>();

    @Property("affectedServices")
    private List<String> affectedServices = new ArrayList<>();
}
