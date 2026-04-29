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
@Node("SecurityFinding")
public class SecurityFindingNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("scanner")
    private String scanner;

    @Property("severity")
    private String severity;

    @Property("ruleId")
    private String ruleId;

    @Property("description")
    private String description;

    @Property("locator")
    private String locator;

    @Property("commit")
    private String commit;

    @Property("capturedAt")
    private String capturedAt;
}
