package com.assurant.brain.graph.node;

import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.springframework.data.neo4j.core.schema.Id;
import org.springframework.data.neo4j.core.schema.Node;
import org.springframework.data.neo4j.core.schema.Property;

import java.util.ArrayList;
import java.util.List;

@Node("CommunitySummary")
@Getter
@Setter
@ToString
public class CommunitySummaryNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("level")
    private int level;

    @Property("communityKey")
    private String communityKey;

    @Property("memberClassFqns")
    private List<String> memberClassFqns = new ArrayList<>();

    @Property("summary")
    private String summary;

    @Property("memberCountHash")
    private String memberCountHash;

    @Property("capturedAt")
    private String capturedAt;
}
