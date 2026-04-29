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
@Node("Person")
public class PersonNode {

    @Id
    private String id;

    @Property("githubLogin")
    private String githubLogin;

    @Property("email")
    private String email;

    @Property("displayName")
    private String displayName;

    @Property("teamId")
    private String teamId;
}
