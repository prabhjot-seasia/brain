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
@Node("BusinessProcess")
public class BusinessProcessNode {

    @Id
    private String id;

    @Property("name")
    private String name;

    @Property("processType")
    private String processType;

    @Property("ownerTeamId")
    private String ownerTeamId;

    @Property("bpmnUrl")
    private String bpmnUrl;

    @Property("realizedByServiceIds")
    private List<String> realizedByServiceIds = new ArrayList<>();
}
