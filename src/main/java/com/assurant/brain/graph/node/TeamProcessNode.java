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
@Node("TeamProcess")
public class TeamProcessNode {

    @Id
    private String id;

    @Property("teamId")
    private String teamId;

    @Property("sprintCadence")
    private String sprintCadence;

    @Property("sprintLengthDays")
    private int sprintLengthDays;

    @Property("releaseTrainName")
    private String releaseTrainName;

    @Property("ceremoniesUrl")
    private String ceremoniesUrl;
}
