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
@Node("ReviewPattern")
public class ReviewPatternNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("phrase")
    private String phrase;

    @Property("occurrences")
    private int occurrences;

    @Property("reviewers")
    private List<String> reviewers = new ArrayList<>();

    @Property("exemplarPrUrls")
    private List<String> exemplarPrUrls = new ArrayList<>();

    @Property("status")
    private String status;
}
