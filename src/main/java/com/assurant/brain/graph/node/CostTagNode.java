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
@Node("CostTag")
public class CostTagNode {

    @Id
    private String id;

    @Property("projectId")
    private String projectId;

    @Property("tagKey")
    private String tagKey;

    @Property("tagValue")
    private String tagValue;

    @Property("monthlyCostUsd")
    private double monthlyCostUsd;

    @Property("currency")
    private String currency;

    @Property("billingPeriod")
    private String billingPeriod;
}
