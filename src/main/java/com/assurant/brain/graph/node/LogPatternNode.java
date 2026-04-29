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
@Node("LogPattern")
public class LogPatternNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("level")
    private String level;

    @Property("template")
    private String template;

    @Property("exemplarMessage")
    private String exemplarMessage;

    @Property("occurrences")
    private long occurrences;

    @Property("source")
    private String source;

    @Property("capturedAt")
    private String capturedAt;
}
